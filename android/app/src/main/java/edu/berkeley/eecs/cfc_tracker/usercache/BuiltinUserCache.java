package edu.berkeley.eecs.cfc_tracker.usercache;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.JsonReader;

import com.google.gson.Gson;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedList;

import edu.berkeley.eecs.cfc_tracker.Log;
import edu.berkeley.eecs.cfc_tracker.wrapper.Entry;
import edu.berkeley.eecs.cfc_tracker.wrapper.Metadata;

/**
 * Concrete implementation of the user cache that stores the entries
 * in an SQLite database.
 *
 * Big design question: should we store the data in separate tables which are put
 * in here
 */
public class BuiltinUserCache extends SQLiteOpenHelper implements UserCache {

    // All Static variables
    // Database Version
    private static final int DATABASE_VERSION = 1;

    // Database Name
    private static final String DATABASE_NAME = "userCacheDB";

    // Table names
    private static final String TABLE_USER_CACHE = "userCache";

    // USER_CACHE Table Columns names
    // We expand the metadata and store the data as a JSON blob
    private static final String KEY_WRITE_TS = "write_ts";
    private static final String KEY_READ_TS = "read_ts";
    private static final String KEY_TYPE = "type";
    private static final String KEY_KEY = "key";
    private static final String KEY_PLUGIN = "plugin";
    private static final String KEY_DATA = "data";

    private static final String TAG = "BuiltinUserCache";

    private static final String METADATA_TAG = "metadata";
    private static final String DATA_TAG = "data";

    private static final String MESSAGE_TYPE = "message";
    private static final String DOCUMENT_TYPE = "document";
    private static final String RW_DOCUMENT_TYPE = "rw-document";

    private Context cachedCtx;

    public BuiltinUserCache(Context ctx) {
        super(ctx, DATABASE_NAME, null, DATABASE_VERSION);
        cachedCtx = ctx;
    }

    @Override
    public void onCreate(SQLiteDatabase sqLiteDatabase) {
        String CREATE_USER_CACHE_TABLE = "CREATE TABLE " + TABLE_USER_CACHE +" (" +
                KEY_WRITE_TS + " INTEGER, "+ KEY_READ_TS +" INTEGER, " +
                KEY_TYPE + " TEXT, " + KEY_KEY + " TEXT, "+
                KEY_PLUGIN + " TEXT, " + KEY_DATA + " TEXT)";
        System.out.println("CREATE_CLIENT_STATS_TABLE = " + CREATE_USER_CACHE_TABLE);
        sqLiteDatabase.execSQL(CREATE_USER_CACHE_TABLE);
    }

    @Override
    public void putMessage(String key, Object value) {
        putValue(key, value, MESSAGE_TYPE);
    }

    @Override
    public void putReadWriteDocument(String key, Object value) {
        putValue(key, value, RW_DOCUMENT_TYPE);
    }

    private void putValue(String key, Object value, String type) {
        SQLiteDatabase db = this.getWritableDatabase();

        ContentValues newValues = new ContentValues();
        newValues.put(KEY_WRITE_TS, System.currentTimeMillis());
        newValues.put(KEY_TYPE, type);
        newValues.put(KEY_KEY, key);
        newValues.put(KEY_DATA, new Gson().toJson(value));
        db.insert(TABLE_USER_CACHE, null, newValues);
    }

    @Override
    public <T> T getDocument(String key, Class<T> classOfT) {
        SQLiteDatabase db = this.getReadableDatabase();
        String selectQuery = "SELECT "+KEY_DATA+" from " + TABLE_USER_CACHE +
                "WHERE " + KEY_KEY + " = " + key +
                " AND ("+ KEY_TYPE + " = "+ DOCUMENT_TYPE + " OR " + KEY_TYPE + " = " + RW_DOCUMENT_TYPE + ")";
        Cursor queryVal = db.rawQuery(selectQuery, null);
        T retVal = new Gson().fromJson(queryVal.getString(0), classOfT);
        db.close();
        updateReadTimestamp(key);
        return retVal;
    }

    @Override
    public JSONObject getUpdatedDocument(String key) {
        SQLiteDatabase db = this.getReadableDatabase();
        String selectQuery = "SELECT "+KEY_WRITE_TS+", "+KEY_READ_TS+", "+KEY_DATA+" from " + TABLE_USER_CACHE +
                "WHERE " + KEY_KEY + " = " + key +
                " AND ("+ KEY_TYPE + " = "+ DOCUMENT_TYPE + " OR " + KEY_TYPE + " = " + RW_DOCUMENT_TYPE + ")";
        Cursor queryVal = db.rawQuery(selectQuery, null);
        long writeTs = queryVal.getLong(0);
        long readTs = queryVal.getLong(1);
        if (writeTs < readTs) {
            // This has been not been updated since it was last read
            return null;
        }
        JSONObject dataObj = null;
        try {
            dataObj = new JSONObject(queryVal.getString(0));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        db.close();
        updateReadTimestamp(key);
        return dataObj;
    }

    private void updateReadTimestamp(String key) {
        SQLiteDatabase writeDb = this.getWritableDatabase();
        ContentValues updateValues = new ContentValues();
        updateValues.put(KEY_READ_TS, System.currentTimeMillis());
        updateValues.put(KEY_KEY, key);
        writeDb.update(TABLE_USER_CACHE, updateValues, null, null);
        writeDb.close();
    }

    @Override
    public void clearMessages(TimeQuery tq) {
        String whereString = tq.key + " < ? AND " + tq.key + " > ?";
        String[] whereArgs = {String.valueOf(tq.startTs), String.valueOf(tq.endTs)};
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_USER_CACHE, whereString, whereArgs);
        db.close();
    }

    /*
     * Nuclear option that just deletes everything. Useful for debugging.
     */
    public void clear() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_USER_CACHE, null, null);
        db.close();
    }

    @Override
    public void onUpgrade(SQLiteDatabase sqLiteDatabase, int oldVersion, int newVersion) {
        sqLiteDatabase.execSQL("DROP TABLE IF EXISTS "+TABLE_USER_CACHE);
        onCreate(sqLiteDatabase);
    }

    /* BEGIN: Methods that are invoked to get the data for syncing to the host
     * Note that these are not defined in the interface, since other methods for syncing,
     * such as couchdb and azure, have their own syncing mechanism that don't depend on our
     * REST API.
     */

    /*
     * Return a string version of the messages and rw documents that need to be sent to the server.
     */

    public Entry[] sync_phone_to_server() {
        String selectQuery = "SELECT * from " + TABLE_USER_CACHE +
                "WHERE " + KEY_TYPE + " = "+ DOCUMENT_TYPE + " OR " + KEY_TYPE + " = " + RW_DOCUMENT_TYPE +
                "SORT BY "+KEY_WRITE_TS;

        SQLiteDatabase db = this.getReadableDatabase();
        Cursor queryVal = db.rawQuery(selectQuery, null);

        int resultCount = queryVal.getCount();
        Entry[] entryArray = new Entry[resultCount];

        // Returns fals if the cursor is empty
        // in which case we return the empty JSONArray, to be consistent.
        if (queryVal.moveToFirst()) {
            for (int i = 0; i < resultCount; i++) {
                Metadata md = new Metadata();
                md.setWrite_ts(queryVal.getLong(0));
                md.setRead_ts(queryVal.getLong(1));
                md.setType(queryVal.getString(2));
                md.setKey(queryVal.getString(3));
                md.setPlugin(queryVal.getString(4));
                String dataStr = queryVal.getString(5);
                Entry entry = new Entry();
                entry.setMetadata(md);
                entry.setData(dataStr);
                Log.d(cachedCtx, TAG, "For row " + i + ", about to send string " + new Gson().toJson(entry));
                entryArray[i] = entry;
            }
        }
        return entryArray;
    }

    public void sync_server_to_phone(Entry[] entryArray) {
        SQLiteDatabase db = this.getReadableDatabase();
        for (Entry entry:entryArray) {
            ContentValues newValues = new ContentValues();
            newValues.put(KEY_WRITE_TS, entry.getMetadata().getWrite_ts());
            newValues.put(KEY_READ_TS, entry.getMetadata().getRead_ts());
            newValues.put(KEY_TYPE, entry.getMetadata().getType());
            newValues.put(KEY_KEY, entry.getMetadata().getKey());
            newValues.put(KEY_PLUGIN, entry.getMetadata().getPlugin());
            newValues.put(KEY_DATA, (String) entry.getData());
            db.insert(TABLE_USER_CACHE, null, newValues);
        }
        db.close();
    }

    // END: Methods invoked for syncing the data to the host. Not part of the interface.
}
