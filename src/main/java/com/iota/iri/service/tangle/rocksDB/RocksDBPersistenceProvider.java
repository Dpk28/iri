package com.iota.iri.service.tangle.rocksDB;

import com.iota.iri.conf.Configuration;
import com.iota.iri.model.*;
import com.iota.iri.service.storage.AbstractStorage;
import com.iota.iri.service.tangle.IPersistenceProvider;
import com.iota.iri.service.tangle.Serializer;
import com.iota.iri.service.viewModels.TransactionViewModel;
import com.iota.iri.utils.Converter;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.NotImplementedException;
import org.rocksdb.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by paul on 3/2/17 for iri.
 */
public class RocksDBPersistenceProvider implements IPersistenceProvider {

    private static int BLOOM_FILTER_RANGE = 1<<1;

    private String[] columnFamilyNames = new String[]{
            "transaction",
            "transactionValidity",
            "transactionType",
            "transactionArrivalTime",
            "address",
            "bundle",
            "approovee",
            "tag",
            "flag",
            "tip",
            "scratchpad",
            "analyzedFlag",
            "analyzedTipFlag",
    };

    boolean running;
    private ColumnFamilyHandle transactionHandle;
    private ColumnFamilyHandle transactionValidityHandle;
    private ColumnFamilyHandle transactionTypeHandle;
    private ColumnFamilyHandle transactionArrivalTimeHandle;
    private ColumnFamilyHandle addressHandle;
    private ColumnFamilyHandle bundleHandle;
    private ColumnFamilyHandle approoveeHandle;
    private ColumnFamilyHandle tagHandle;
    private ColumnFamilyHandle flagHandle;
    private ColumnFamilyHandle tipHandle;
    private ColumnFamilyHandle scratchpadHandle;
    private ColumnFamilyHandle analyzedFlagHandle;
    private ColumnFamilyHandle analyzedTipHandle;

    List<ColumnFamilyHandle> transactionGetList;

    RocksDB db;
    DBOptions options;
    private Random random;
    private Thread compactionThreadHandle;

    @Override
    public void init(String path) throws Exception{
        initDB(path, path+".log");
    }

    @Override
    public void init() throws Exception {
        initDB(
                Configuration.string(Configuration.DefaultConfSettings.DB_PATH),
                Configuration.string(Configuration.DefaultConfSettings.DB_LOG_PATH)
        );
    }

    @Override
    public void shutdown() {
        running = false;
        try {
            compactionThreadHandle.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if (db != null) db.close();
        options.close();
    }


    @Override
    public boolean saveTransaction(Transaction transaction) throws RocksDBException, IOException {
        WriteBatch batch = new WriteBatch();
        byte[] key = transaction.hash.bytes();
        batch.put(transactionHandle, key, transaction.bytes);
        batch.put(transactionValidityHandle, key, Serializer.serialize(transaction.validity));
        batch.put(transactionTypeHandle, key, Serializer.serialize(transaction.type));
        batch.put(transactionArrivalTimeHandle, key, Serializer.serialize(transaction.arrivalTime));
        batch.merge(addressHandle, transaction.address.hash.bytes(), key);
        batch.merge(bundleHandle, transaction.bundle.hash.bytes(), key);
        batch.merge(approoveeHandle, transaction.trunk.hash.bytes(), key);
        batch.merge(approoveeHandle, transaction.branch.hash.bytes(), key);
        batch.merge(tagHandle, transaction.tag.value.bytes(), key);
        db.write(new WriteOptions(), batch);
        return true;
    }


    @Override
    public boolean save(Object thing) throws Exception {
        if(thing instanceof Transaction) {
            saveTransaction((Transaction) thing);
        } else if(thing instanceof Tip) {
            saveTip((Tip) thing);
        } else if(thing instanceof Scratchpad) {
            saveScratchpad((Scratchpad) thing);
        } else if(thing instanceof AnalyzedFlag) {
            return saveAnalyzedTransactionFlag(((AnalyzedFlag) thing));
        } else if(thing instanceof Flag) {
            saveFlag((Flag) thing);
        } else {
            return false;
        }
        return true;
    }

    public boolean saveAnalyzedTransactionFlag(AnalyzedFlag flag) throws RocksDBException {
        byte[] key = flag.hash.bytes();
        if(db.get(analyzedFlagHandle, key) == null) {
            db.put(analyzedFlagHandle, key, flag.status);
            return false;
        } else {
            return true;
        }
    }

    private void saveScratchpad(Scratchpad scratchpad) throws RocksDBException {
        byte[] key = scratchpad.hash.bytes();
        if(db.get(transactionHandle, key) == null && !db.keyMayExist(scratchpadHandle, key, stringBuffer))
            db.put(scratchpadHandle, key, scratchpad.status);
    }

    private void saveFlag(Flag flag) throws RocksDBException {
        byte[] key = flag.hash.bytes();
        if(db.get(flagHandle, key) == null)
            db.put(flagHandle, key, flag.status);
    }

    private void saveTip(Tip tip) throws RocksDBException {
        byte[] key = tip.hash.bytes();
        db.put(tipHandle, key, tip.status);
    }

    StringBuffer stringBuffer = new StringBuffer();
    @Override
    public void delete(Object thing) throws Exception {
        byte[] key;
        if(thing instanceof Tip) {
            key = ((Tip) thing).hash.bytes();
            db.delete(tipHandle, key);
        } else if(thing instanceof Scratchpad) {
            key = ((Scratchpad) thing).hash.bytes();
            if(db.keyMayExist(scratchpadHandle, key, stringBuffer))
                db.delete(scratchpadHandle, key);
        } else if(thing instanceof Transaction) {
            deleteTransaction((Transaction) thing);
        } else if(thing instanceof AnalyzedFlag) {
            key = ((AnalyzedFlag) thing).hash.bytes();
            db.delete(analyzedFlagHandle, key);
        } else if(thing instanceof Flag) {
            key = ((Flag) thing).hash.bytes();
            db.delete(flagHandle, key);
        }
    }

    private void deleteTransaction(Transaction transaction) throws RocksDBException {
        byte[] key = transaction.hash.bytes();
        db.delete(transactionHandle, key);
        db.delete(transactionArrivalTimeHandle, key);
        db.delete(transactionTypeHandle, key);
        db.delete(transactionValidityHandle, key);
        if(db.get(tipHandle, key) != null)
            db.delete(tipHandle, key);
    }



    private Hash[] byteToHash(byte[] bytes, int size) {
        if(bytes == null) {
            return new Hash[0];
        }
        int i;
        Set<Hash> hashes = new TreeSet<>();
        for(i = size; i <= bytes.length; i += size + 1) {
            hashes.add(new Hash(Arrays.copyOfRange(bytes, i - size, i)));
        }
        return hashes.stream().toArray(Hash[]::new);
    }

    @Override
    public boolean save(int uuid, Object model) throws Exception {
        byte[] key = getTransientKey(uuid, ((Flag) model).hash.bytes());
        boolean exists = db.keyMayExist(analyzedTipHandle, key, new StringBuffer());
        if (model instanceof AnalyzedFlag) {
            db.put(analyzedTipHandle, key, Serializer.serialize(((AnalyzedFlag) model).status));
        } else if(model instanceof Flag) {
            db.put(analyzedTipHandle, key, Serializer.serialize(((Flag) model).status));
        }
        return exists;
    }

    @Override
    public boolean mayExist(int handle, Hash key) throws Exception {
        byte[] transientKey = getTransientKey(handle, key.bytes());
        boolean mayExist = db.keyMayExist(analyzedTipHandle, transientKey, new StringBuffer());
        return mayExist;
    }

    @Override
    public boolean exists(Class<?> model, Hash key) throws Exception {
        if(model == Transaction.class) {
            return db.get(transactionHandle, key.bytes()) != null;
        } else if (model == AnalyzedFlag.class) {
            return db.get(analyzedFlagHandle, key.bytes()) != null;
        }
        throw new NotImplementedException("Mada mada exists shinai");
    }

    @Override
    public Object get(int uuid, Class<?> model, Hash key) throws Exception {
        Object out = null;
        byte[] result;
        Flag flag;
        if (model == AnalyzedFlag.class) {
            result = db.get(analyzedTipHandle, getTransientKey(uuid, key.bytes()));
            if(result != null) {
                flag = new AnalyzedFlag();
                flag.hash = (key);
                out = flag;
            }
        } else if(model == Flag.class) {
            result = db.get(analyzedTipHandle, getTransientKey(uuid, key.bytes()));
            if(result != null) {
                flag = new Flag();
                flag.hash = (key);
                out = flag;
            }
        }
        return out;
    }


    @Override
    public void deleteTransientObject(int uuid, Hash key) throws Exception {
        byte[] tableKey = getTransientKey(uuid, key.bytes());
        if(db.get(analyzedTipHandle, (tableKey)) != null) {
            db.delete(analyzedTipHandle, tableKey);
        }
    }

    @Override
    public void copyTransientList(int sourceId, int destId) throws Exception {
        RocksIterator iterator;
        WriteBatch batch = new WriteBatch();
        iterator = db.newIterator(analyzedTipHandle);
        byte[] sourcePre = Serializer.serialize(sourceId);
        byte[] sourceStart = getTransientKey(sourceId, TransactionViewModel.NULL_TRANSACTION_HASH_BYTES);
        byte[] destPre = Serializer.serialize(destId);
        byte[] destKey;
        iterator.seek(sourceStart);
        iterator.next();
        for(; iterator.isValid(); iterator.next()) {
            if(!Arrays.equals(sourcePre, Arrays.copyOfRange(iterator.key(), 0, sourcePre.length)))
                break;
            destKey = ArrayUtils.addAll(destPre, Arrays.copyOfRange(iterator.key(), sourcePre.length, iterator.key().length));
            batch.put(analyzedTipHandle, destKey, iterator.value());
        }
        db.write(new WriteOptions(), batch);
    }

    @Override
    public Object latest(Class<?> model) throws Exception{
        RocksIterator iterator;
        Object out = null;
        if(model == Scratchpad.class) {
            Scratchpad scratchpad = new Scratchpad();
            byte[] randomPosition = new byte[Hash.SIZE_IN_BYTES];
            random.nextBytes(randomPosition);
            iterator = db.newIterator(scratchpadHandle);
            iterator.seek(randomPosition);
            iterator.next();
            if(!iterator.isValid()) {
                iterator.seek(randomPosition);
                iterator.prev();
            }
            if(!iterator.isValid()) {
                iterator.seekToFirst();
            }
            if(iterator.isValid()) {
                scratchpad.hash = new Hash(iterator.key());
            }
            out = scratchpad;
            iterator.close();
        }
        return out;
    }

    @Override
    public Object[] getKeys(Class<?> modelClass) throws Exception {
        RocksIterator iterator;
        Object[] out = null;
        if(modelClass == Tip.class) {
            List<Tip> tips = new ArrayList<>();
            iterator = db.newIterator(tipHandle);
            for(iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                tips.add(new Tip(new Hash(iterator.key())));
            }
            out = tips.toArray();
        }
        return out;
    }

    @Override
    public boolean get(Transaction transaction) throws Exception {
        byte[] key = transaction.hash.bytes();
        transaction.bytes = db.get(transactionHandle, key);
        if(transaction.bytes == null) {
            transaction.type = AbstractStorage.PREFILLED_SLOT;
            return false;
        } else if (transaction.bytes.length != TransactionViewModel.SIZE) {
            deleteTransaction(transaction);
            transaction.type = AbstractStorage.PREFILLED_SLOT;
            return false;
        }
        transaction.validity = Serializer.getInteger(db.get(transactionValidityHandle, key));
        transaction.type = Serializer.getInteger(db.get(transactionTypeHandle, key));
        transaction.arrivalTime = Serializer.getLong(db.get(transactionArrivalTimeHandle, key));
        return true;
    }

    @Override
    public boolean get(Address address) throws Exception {
        byte[] result = db.get(addressHandle, address.hash.bytes());
        if(result != null) {
            address.transactions = byteToHash(result, Hash.SIZE_IN_BYTES);
            return  true;
        } else {
            address.transactions = new Hash[0];
            return false;
        }
    }

    @Override
    public boolean get(Tag tag) throws Exception {
        byte[] result = db.get(tagHandle, tag.value.bytes());
        if(result != null) {
            tag.transactions = byteToHash(result, Hash.SIZE_IN_BYTES);
            return  true;
        } else {
            tag.transactions = new Hash[0];
            return false;
        }
    }

    @Override
    public boolean get(Bundle bundle) throws Exception {
        byte[] result = db.get(bundleHandle, bundle.hash.bytes());
        if(result != null) {
            bundle.transactions = byteToHash(result, Hash.SIZE_IN_BYTES);
            return true;
        }
        return false;
    }

    @Override
    public boolean get(Approvee approvee) throws Exception {
        byte[] result = db.get(approoveeHandle, approvee.hash.bytes());
        if(result != null) {
            approvee.transactions = byteToHash(result, Hash.SIZE_IN_BYTES);
            return true;
        } else {
            approvee.transactions = new Hash[0];
        return false;
        }
    }

    @Override
    public boolean mayExist(Scratchpad scratchpad) throws Exception {
        return db.keyMayExist(scratchpadHandle, scratchpad.hash.bytes(), new StringBuffer());
    }

    @Override
    public boolean mayExist(Transaction transaction) throws Exception {
        byte[] key = transaction.hash.bytes();
        return db.keyMayExist(transactionHandle, key, new StringBuffer());
    }

    @Override
    public boolean mayExist(Tip tip) throws Exception {
        return db.keyMayExist(tipHandle, tip.hash.bytes(), new StringBuffer());
    }

    @Override
    public void updateType(Transaction transaction) throws Exception {
        byte[] key = transaction.hash.bytes();
        db.put(transactionTypeHandle, key, Serializer.serialize(transaction.type));
    }

    @Override
    public boolean transientObjectExists(int uuid, Hash hash) throws Exception {
        return db.get(analyzedTipHandle, getTransientKey(uuid, hash.bytes())) != null;
    }

    @Override
    public void flushAnalyzedFlags() throws Exception {
        db.flush(new FlushOptions().setWaitForFlush(true), analyzedFlagHandle);
        RocksIterator iterator = db.newIterator(analyzedFlagHandle);
        for(iterator.seekToLast(); iterator.isValid(); iterator.prev()) {
            db.delete(analyzedFlagHandle, iterator.key());
        }
    }

    @Override
    public void flushScratchpad() throws Exception {
        db.flush(new FlushOptions().setWaitForFlush(true), scratchpadHandle);
        RocksIterator iterator = db.newIterator(scratchpadHandle);
        for(iterator.seekToLast(); iterator.isValid(); iterator.prev()) {
            db.delete(scratchpadHandle, iterator.key());
        }
    }

    @Override
    public long getNumberOfTransactions() throws Exception {
        return db.getLongProperty(transactionHandle, "rocksdb.estimate-num-keys");
    }

    @Override
    public long getNumberOfRequestedTransactions() throws Exception {
        return db.getLongProperty(scratchpadHandle, "rocksdb.estimate-num-keys");
    }

    @Override
    public boolean transactionExists(Hash hash) throws Exception {
        return db.get(transactionHandle, hash.bytes()) != null;
    }

    @Override
    public boolean setTransientFlagHandle(int uuid) throws RocksDBException {
        return true;
    }

    @Override
    public void flushTagRange(int id) throws Exception {
        int i = id;
        byte[] idbytes = Serializer.serialize(i);
        byte[] start = getTransientKey(i, TransactionViewModel.NULL_TRANSACTION_HASH_BYTES);
        byte[] keyStart;
        RocksIterator iterator = db.newIterator(analyzedTipHandle);
        iterator.seek(start);
        iterator.next();
        for(; iterator.isValid(); iterator.next()) {
            keyStart = Arrays.copyOfRange(iterator.key(), 0, idbytes.length);
            if(!Arrays.equals(idbytes, keyStart)) break;
            db.delete(analyzedTipHandle, iterator.key());
        }
        //db.deleteRange(analyzedTipHandle, start, end);
    }

    @Override
    public boolean update(Object thing, String item) throws Exception {
        if(thing instanceof Transaction) {
            Transaction transaction = (Transaction) thing;
            byte[] key = transaction.hash.bytes();
            switch (item) {
                case "validity":
                    db.put(transactionValidityHandle, key, Serializer.serialize(transaction.validity));
                    break;
                case "type":
                    db.put(transactionValidityHandle, key, Serializer.serialize(transaction.type));
                    break;
                case "arrivalTime":
                    db.put(transactionValidityHandle, key, Serializer.serialize(transaction.arrivalTime));
                    break;
                default:
                    throw new NotImplementedException("Mada Sono Update ga dekinai yo");
            }
        } else {
            throw new NotImplementedException("Mada Sono Update ga dekinai yo");
        }
        return true;
    }

    private byte[] getTransientKey(int handle, byte[] key) throws IOException {
        return ArrayUtils.addAll(Serializer.serialize(handle), key);
    }


    void initDB(String path, String logPath) throws Exception {
        random = new Random();
        StringAppendOperator stringAppendOperator = new StringAppendOperator();
        RocksDB.loadLibrary();
        Thread.yield();
        BloomFilter bloomFilter = new BloomFilter(BLOOM_FILTER_RANGE);
        BlockBasedTableConfig blockBasedTableConfig = new BlockBasedTableConfig().setFilter(bloomFilter);
        options = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true).setDbLogDir(logPath);

        List<ColumnFamilyHandle> familyHandles = new ArrayList<>();
        List<ColumnFamilyDescriptor> familyDescriptors = Arrays.stream(columnFamilyNames)
                .map(name -> new ColumnFamilyDescriptor(name.getBytes(),
                        new ColumnFamilyOptions()
                                .setMergeOperator(stringAppendOperator).setTableFormatConfig(blockBasedTableConfig))).collect(Collectors.toList());

        familyDescriptors.add(0, new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, new ColumnFamilyOptions()));

        fillMissingColumns(familyDescriptors, familyHandles, path);

        db = RocksDB.open(options, path, familyDescriptors, familyHandles);

        fillmodelColumnHandles(familyDescriptors, familyHandles);
    }

    private void fillMissingColumns(List<ColumnFamilyDescriptor> familyDescriptors, List<ColumnFamilyHandle> familyHandles, String path) throws Exception {
        List<ColumnFamilyDescriptor> columnFamilies = RocksDB.listColumnFamilies(new Options().setCreateIfMissing(true), path)
                .stream()
                .map(b -> new ColumnFamilyDescriptor(b, new ColumnFamilyOptions()))
                .collect(Collectors.toList());
        columnFamilies.add(0, familyDescriptors.get(0));
        List<ColumnFamilyDescriptor> missingFromDatabase = familyDescriptors.stream().filter(d -> columnFamilies.stream().filter(desc -> new String(desc.columnFamilyName()).equals(new String(d.columnFamilyName()))).toArray().length == 0).collect(Collectors.toList());
        List<ColumnFamilyDescriptor> missingFromDescription = columnFamilies.stream().filter(d -> familyDescriptors.stream().filter(desc -> new String(desc.columnFamilyName()).equals(new String(d.columnFamilyName()))).toArray().length == 0).collect(Collectors.toList());
        if (missingFromDatabase.size() != 0) {
            missingFromDatabase.remove(familyDescriptors.get(0));
            db = RocksDB.open(options, path, columnFamilies, familyHandles);
            for (ColumnFamilyDescriptor description : missingFromDatabase) {
                addColumnFamily(description.columnFamilyName(), db);
            }
            db.close();
        }
        if (missingFromDescription.size() != 0) {
            missingFromDescription.stream().forEach(familyDescriptors::add);
        }
        running = true;
        this.compactionThreadHandle = new Thread(() -> {
            long compationWaitTime = 5 * 60 * 1000;
            while(running) {
                try {
                    for(ColumnFamilyHandle handle: familyHandles) {
                            db.compactRange(handle);
                    }
                    Thread.sleep(compationWaitTime);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (RocksDBException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private void addColumnFamily(byte[] familyName, RocksDB db) throws RocksDBException {
        final ColumnFamilyHandle columnFamilyHandle = db.createColumnFamily(
                new ColumnFamilyDescriptor(familyName,
                        new ColumnFamilyOptions()));
        assert (columnFamilyHandle != null);
    }

    private void fillmodelColumnHandles(List<ColumnFamilyDescriptor> familyDescriptors, List<ColumnFamilyHandle> familyHandles) throws RocksDBException {
        int i = 0;
        transactionHandle = familyHandles.get(++i);
        transactionValidityHandle = familyHandles.get(++i);
        transactionTypeHandle = familyHandles.get(++i);
        transactionArrivalTimeHandle = familyHandles.get(++i);
        addressHandle = familyHandles.get(++i);
        bundleHandle = familyHandles.get(++i);
        approoveeHandle = familyHandles.get(++i);
        tagHandle = familyHandles.get(++i);
        flagHandle = familyHandles.get(++i);
        tipHandle = familyHandles.get(++i);
        scratchpadHandle = familyHandles.get(++i);
        analyzedFlagHandle = familyHandles.get(++i);
        analyzedTipHandle = familyHandles.get(++i);

        initFlushFlags();
        updateTagDB();
        scanTxDeleteBaddies();

        db.compactRange();

        transactionGetList = new ArrayList<>();
        for(i = 1; i < 5; i ++) {
            transactionGetList.add(familyHandles.get(i));
        }
    }

    private void scanTxDeleteBaddies() throws RocksDBException {
        RocksIterator iterator = db.newIterator(transactionHandle);
        List<byte[]> baddies = new ArrayList<>();
        for(iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
            if(iterator.value().length != TransactionViewModel.SIZE || Arrays.equals(iterator.value(), TransactionViewModel.NULL_TRANSACTION_BYTES)) {
                baddies.add(iterator.key());
            }
        }
        Transaction transaction;
        for(byte[] baddie : baddies) {
            transaction = new Transaction();
            transaction.hash = new Hash(baddie);
            deleteTransaction(transaction);
        }
    }

    private void initFlushFlags() throws RocksDBException {
        db.flush(new FlushOptions().setWaitForFlush(true), analyzedTipHandle);
        RocksIterator iterator = db.newIterator(analyzedTipHandle);
        for(iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
            db.delete(analyzedTipHandle, iterator.key());
        }
        db.flush(new FlushOptions().setWaitForFlush(true), analyzedFlagHandle);
        iterator = db.newIterator(analyzedFlagHandle);
        for(iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
            db.delete(analyzedFlagHandle, iterator.key());
        }

    }

    private void updateTagDB() throws RocksDBException {

        RocksIterator iterator = db.newIterator(tagHandle);
        byte[] res, key;
        WriteBatch batch = new WriteBatch();
        for(iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
            if(iterator.key().length < Hash.SIZE_IN_BYTES) {
                key = ArrayUtils.addAll(iterator.key(), Arrays.copyOf(TransactionViewModel.NULL_TRANSACTION_HASH_BYTES, Hash.SIZE_IN_BYTES - iterator.key().length));
                batch.put(key, iterator.value());
                db.delete(iterator.key());
            }
        }
        db.write(new WriteOptions(), batch);
    }
}
