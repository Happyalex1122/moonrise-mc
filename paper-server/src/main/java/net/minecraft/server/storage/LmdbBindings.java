package net.minecraft.server.storage;

import java.io.File;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.function.BiConsumer;

public class LmdbBindings {
    // Struct layout for MDB_val: size_t (8 bytes) + void* (8 bytes) = 16 bytes
    public static final StructLayout MDB_VAL_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("mv_size"),
        ValueLayout.ADDRESS.withName("mv_data")
    );

    private static final Arena LIBRARY_ARENA;
    private static final Linker LINKER;
    private static SymbolLookup LOOKUP;
    private static boolean AVAILABLE;

    private static MethodHandle mdb_env_create;
    private static MethodHandle mdb_env_set_mapsize;
    private static MethodHandle mdb_env_set_maxdbs;
    private static MethodHandle mdb_env_open;
    private static MethodHandle mdb_txn_begin;
    private static MethodHandle mdb_dbi_open;
    private static MethodHandle mdb_put;
    private static MethodHandle mdb_get;
    private static MethodHandle mdb_del;
    private static MethodHandle mdb_txn_commit;
    private static MethodHandle mdb_txn_abort;
    private static MethodHandle mdb_env_close;
    private static MethodHandle mdb_strerror;
    private static MethodHandle mdb_cursor_open;
    private static MethodHandle mdb_cursor_get;
    private static MethodHandle mdb_cursor_close;

    public static final int MDB_RDONLY = 0x20000;
    public static final int MDB_CREATE = 0x40000;

    // Cursor operations
    public static final int MDB_FIRST = 0;
    public static final int MDB_NEXT = 8;
    public static final int MDB_NOTFOUND = -30798;

    static {
        LIBRARY_ARENA = Arena.ofShared();
        LINKER = Linker.nativeLinker();
        SymbolLookup lookup = null;
        String[] libNames = {"lmdb", "liblmdb"};
        for (String name : libNames) {
            try {
                lookup = SymbolLookup.libraryLookup(name, LIBRARY_ARENA);
                break;
            } catch (Exception ignore) {}
        }
        if (lookup == null) {
            try {
                lookup = SymbolLookup.loaderLookup();
            } catch (Exception ignore) {}
        }
        LOOKUP = lookup;
        AVAILABLE = false;

        if (LOOKUP != null) {
            try {
                mdb_env_create = LINKER.downcallHandle(LOOKUP.find("mdb_env_create").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                mdb_env_set_mapsize = LINKER.downcallHandle(LOOKUP.find("mdb_env_set_mapsize").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
                mdb_env_set_maxdbs = LINKER.downcallHandle(LOOKUP.find("mdb_env_set_maxdbs").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                mdb_env_open = LINKER.downcallHandle(LOOKUP.find("mdb_env_open").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                mdb_txn_begin = LINKER.downcallHandle(LOOKUP.find("mdb_txn_begin").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                mdb_dbi_open = LINKER.downcallHandle(LOOKUP.find("mdb_dbi_open").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                mdb_put = LINKER.downcallHandle(LOOKUP.find("mdb_put").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                mdb_get = LINKER.downcallHandle(LOOKUP.find("mdb_get").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                mdb_del = LINKER.downcallHandle(LOOKUP.find("mdb_del").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                mdb_txn_commit = LINKER.downcallHandle(LOOKUP.find("mdb_txn_commit").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                mdb_txn_abort = LINKER.downcallHandle(LOOKUP.find("mdb_txn_abort").orElseThrow(),
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
                mdb_env_close = LINKER.downcallHandle(LOOKUP.find("mdb_env_close").orElseThrow(),
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
                mdb_strerror = LINKER.downcallHandle(LOOKUP.find("mdb_strerror").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                mdb_cursor_open = LINKER.downcallHandle(LOOKUP.find("mdb_cursor_open").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                mdb_cursor_get = LINKER.downcallHandle(LOOKUP.find("mdb_cursor_get").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                mdb_cursor_close = LINKER.downcallHandle(LOOKUP.find("mdb_cursor_close").orElseThrow(),
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
                AVAILABLE = true;
            } catch (Throwable t) {
                LOOKUP = null;
                AVAILABLE = false;
            }
        }
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static void checkRc(int rc) {
        if (rc != 0) {
            if (mdb_strerror != null) {
                try {
                    MemorySegment strPtr = (MemorySegment) mdb_strerror.invokeExact(rc);
                    throw new RuntimeException("LMDB Error (" + rc + "): " + strPtr.getString(0));
                } catch (Throwable t) {
                    throw new RuntimeException("LMDB Error (" + rc + ") and failed to get error string", t);
                }
            } else {
                throw new RuntimeException("LMDB Error (" + rc + ")");
            }
        }
    }

    public static class Env implements AutoCloseable {
        private final MemorySegment env;

        public Env(String path, long mapSize, int maxDbs) {
            if (LOOKUP == null) {
                throw new IllegalStateException("LMDB library not loaded.");
            }
            try {
                try (Arena localArena = Arena.ofConfined()) {
                    MemorySegment envPtr = localArena.allocate(ValueLayout.ADDRESS);
                    checkRc((int) mdb_env_create.invokeExact(envPtr));
                    this.env = envPtr.get(ValueLayout.ADDRESS, 0);
                }

                checkRc((int) mdb_env_set_mapsize.invokeExact(env, mapSize));
                if (maxDbs > 0) {
                    checkRc((int) mdb_env_set_maxdbs.invokeExact(env, maxDbs));
                }

                File dir = new File(path);
                if (!dir.exists()) {
                    dir.mkdirs();
                }

                try (Arena localArena = Arena.ofConfined()) {
                    MemorySegment pathSeg = localArena.allocateFrom(path);
                    checkRc((int) mdb_env_open.invokeExact(env, pathSeg, 0, 436)); // 0664 octal = 436 decimal
                }
            } catch (Throwable t) {
                throw new RuntimeException("Failed to initialize LMDB Env at " + path, t);
            }
        }

        public Txn beginTxn(boolean readOnly) {
            try {
                try (Arena localArena = Arena.ofConfined()) {
                    MemorySegment txnPtr = localArena.allocate(ValueLayout.ADDRESS);
                    int flags = readOnly ? MDB_RDONLY : 0;
                    checkRc((int) mdb_txn_begin.invokeExact(env, MemorySegment.NULL, flags, txnPtr));
                    return new Txn(txnPtr.get(ValueLayout.ADDRESS, 0), readOnly);
                }
            } catch (Throwable t) {
                throw new RuntimeException("Failed to begin transaction", t);
            }
        }

        public int openDbi(Txn txn, String name, int flags) {
            try {
                try (Arena localArena = Arena.ofConfined()) {
                    MemorySegment nameSeg = name != null ? localArena.allocateFrom(name) : MemorySegment.NULL;
                    MemorySegment dbiPtr = localArena.allocate(ValueLayout.JAVA_INT);
                    checkRc((int) mdb_dbi_open.invokeExact(txn.getSegment(), nameSeg, flags, dbiPtr));
                    return dbiPtr.get(ValueLayout.JAVA_INT, 0);
                }
            } catch (Throwable t) {
                throw new RuntimeException("Failed to open DBI: " + name, t);
            }
        }

        public int openDbi(String name, int flags) {
            try (Txn txn = beginTxn(false)) {
                int dbi = openDbi(txn, name, flags);
                txn.commit();
                return dbi;
            }
        }

        @Override
        public void close() {
            try {
                if (env != null && env.address() != 0) {
                    mdb_env_close.invokeExact(env);
                }
            } catch (Throwable t) {
                System.err.println("Error closing Env:");
                t.printStackTrace();
            }
        }
    }

    public static class Txn implements AutoCloseable {
        private final MemorySegment txn;
        private final boolean readOnly;
        private boolean closed = false;

        public Txn(MemorySegment txn, boolean readOnly) {
            this.txn = txn;
            this.readOnly = readOnly;
        }

        public MemorySegment getSegment() {
            return txn;
        }

        public void commit() {
            if (closed) return;
            try {
                checkRc((int) mdb_txn_commit.invokeExact(txn));
                closed = true;
            } catch (Throwable t) {
                throw new RuntimeException("Failed to commit transaction", t);
            }
        }

        public void abort() {
            if (closed) return;
            try {
                mdb_txn_abort.invokeExact(txn);
                closed = true;
            } catch (Throwable t) {
                throw new RuntimeException("Failed to abort transaction", t);
            }
        }

        public void put(int dbi, byte[] keyBytes, byte[] valBytes, int flags) {
            try (Arena localArena = Arena.ofConfined()) {
                MemorySegment keyData = localArena.allocateFrom(ValueLayout.JAVA_BYTE, keyBytes);
                MemorySegment valData = localArena.allocateFrom(ValueLayout.JAVA_BYTE, valBytes);

                MemorySegment keyStruct = localArena.allocate(MDB_VAL_LAYOUT);
                keyStruct.set(ValueLayout.JAVA_LONG, 0, keyBytes.length);
                keyStruct.set(ValueLayout.ADDRESS, 8, keyData);

                MemorySegment valStruct = localArena.allocate(MDB_VAL_LAYOUT);
                valStruct.set(ValueLayout.JAVA_LONG, 0, valBytes.length);
                valStruct.set(ValueLayout.ADDRESS, 8, valData);

                checkRc((int) mdb_put.invokeExact(txn, dbi, keyStruct, valStruct, flags));
            } catch (Throwable t) {
                throw new RuntimeException("Failed to put key-value", t);
            }
        }

        public byte[] get(int dbi, byte[] keyBytes) {
            try (Arena localArena = Arena.ofConfined()) {
                MemorySegment keyData = localArena.allocateFrom(ValueLayout.JAVA_BYTE, keyBytes);

                MemorySegment keyStruct = localArena.allocate(MDB_VAL_LAYOUT);
                keyStruct.set(ValueLayout.JAVA_LONG, 0, keyBytes.length);
                keyStruct.set(ValueLayout.ADDRESS, 8, keyData);

                MemorySegment valStruct = localArena.allocate(MDB_VAL_LAYOUT);

                int rc = (int) mdb_get.invokeExact(txn, dbi, keyStruct, valStruct);
                if (rc == MDB_NOTFOUND) {
                    return null;
                }
                checkRc(rc);

                long size = valStruct.get(ValueLayout.JAVA_LONG, 0);
                MemorySegment valDataPtr = valStruct.get(ValueLayout.ADDRESS, 8);
                MemorySegment readSegment = valDataPtr.reinterpret(size);
                return readSegment.toArray(ValueLayout.JAVA_BYTE);
            } catch (Throwable t) {
                throw new RuntimeException("Failed to get value", t);
            }
        }

        public boolean delete(int dbi, byte[] keyBytes) {
            try (Arena localArena = Arena.ofConfined()) {
                MemorySegment keyData = localArena.allocateFrom(ValueLayout.JAVA_BYTE, keyBytes);

                MemorySegment keyStruct = localArena.allocate(MDB_VAL_LAYOUT);
                keyStruct.set(ValueLayout.JAVA_LONG, 0, keyBytes.length);
                keyStruct.set(ValueLayout.ADDRESS, 8, keyData);

                int rc = (int) mdb_del.invokeExact(txn, dbi, keyStruct, MemorySegment.NULL);
                if (rc == MDB_NOTFOUND) {
                    return false;
                }
                checkRc(rc);
                return true;
            } catch (Throwable t) {
                throw new RuntimeException("Failed to delete key", t);
            }
        }

        public void forEach(int dbi, BiConsumer<byte[], byte[]> consumer) {
            MemorySegment cursor = MemorySegment.NULL;
            try (Arena localArena = Arena.ofConfined()) {
                MemorySegment cursorPtr = localArena.allocate(ValueLayout.ADDRESS);
                checkRc((int) mdb_cursor_open.invokeExact(txn, dbi, cursorPtr));
                cursor = cursorPtr.get(ValueLayout.ADDRESS, 0);

                MemorySegment keyStruct = localArena.allocate(MDB_VAL_LAYOUT);
                MemorySegment valStruct = localArena.allocate(MDB_VAL_LAYOUT);

                int rc = (int) mdb_cursor_get.invokeExact(cursor, keyStruct, valStruct, MDB_FIRST);
                while (rc == 0) {
                    long keySize = keyStruct.get(ValueLayout.JAVA_LONG, 0);
                    MemorySegment keyDataPtr = keyStruct.get(ValueLayout.ADDRESS, 8);
                    MemorySegment keySegment = keyDataPtr.reinterpret(keySize);
                    byte[] keyBytes = keySegment.toArray(ValueLayout.JAVA_BYTE);

                    long valSize = valStruct.get(ValueLayout.JAVA_LONG, 0);
                    MemorySegment valDataPtr = valStruct.get(ValueLayout.ADDRESS, 8);
                    MemorySegment valSegment = valDataPtr.reinterpret(valSize);
                    byte[] valBytes = valSegment.toArray(ValueLayout.JAVA_BYTE);

                    consumer.accept(keyBytes, valBytes);

                    rc = (int) mdb_cursor_get.invokeExact(cursor, keyStruct, valStruct, MDB_NEXT);
                }
                if (rc != MDB_NOTFOUND) {
                    checkRc(rc);
                }
            } catch (Throwable t) {
                throw new RuntimeException("Error during cursor iteration", t);
            } finally {
                if (cursor != MemorySegment.NULL && cursor.address() != 0) {
                    try {
                        mdb_cursor_close.invokeExact(cursor);
                    } catch (Throwable ignore) {}
                }
            }
        }

        @Override
        public void close() {
            if (!closed) {
                abort();
            }
        }
    }
}
