package org.neo4j.graphalgo.core.utils.paged;

import org.neo4j.collection.pool.MarshlandPool;

import java.util.concurrent.atomic.AtomicLong;

public final class ByteArray extends PagedDataStructure<byte[]> {

    private final AtomicLong allocIdx = new AtomicLong();
    private final MarshlandPool<DeltaCursor> cursors = new MarshlandPool<>(this::newCursor);

    public static ByteArray newArray(long size) {
        return new ByteArray(size);
    }

    private ByteArray(long size) {
        super(size, Byte.BYTES, byte[].class);
    }

    public byte get(long index) {
        assert index < capacity;
        final int pageIndex = pageIndex(index);
        final int indexInPage = indexInPage(index);
        return pages[pageIndex][indexInPage];
    }

    public int getInt(long index) {
        assert index < capacity;
        final int pageIndex = pageIndex(index);
        final int indexInPage = indexInPage(index);
        byte[] page = pages[pageIndex];

        if (page.length - indexInPage >= 4) {
            return getInt(page, indexInPage);
        }
        if (pageIndex + 1 >= pages.length) {
            return -1;
        }
        return getInt(page, pages[pageIndex + 1], indexInPage);
    }

    private int getInt(byte[] page, int offset) {
        return ((page[offset] & 0xFF) << 24) |
                ((page[offset + 1] & 0xFF) << 16) |
                ((page[offset + 2] & 0xFF) << 8) |
                (page[offset + 3] & 0xFF);
    }

    private int getInt(byte[] page, byte[] nextPage, int offset) {
        switch (page.length - offset) {
            case 1:
                return ((page[offset] & 0xFF) << 24) |
                        ((nextPage[0] & 0xFF) << 16) |
                        ((nextPage[1] & 0xFF) << 8) |
                        (nextPage[2] & 0xFF);
            case 2:
                return ((page[offset] & 0xFF) << 24) |
                        ((page[offset + 1] & 0xFF) << 16) |
                        ((nextPage[0] & 0xFF) << 8) |
                        (nextPage[1] & 0xFF);
            case 3:
                return ((page[offset] & 0xFF) << 24) |
                        ((page[offset + 1] & 0xFF) << 16) |
                        ((page[offset + 2] & 0xFF) << 8) |
                        (nextPage[0] & 0xFF);
            default:
                throw new IllegalArgumentException("wrong boundary");
        }
    }

    public byte set(long index, byte value) {
        assert index < capacity;
        final int pageIndex = pageIndex(index);
        final int indexInPage = indexInPage(index);
        final byte[] page = pages[pageIndex];
        final byte ret = page[indexInPage];
        page[indexInPage] = value;
        return ret;
    }

    /**
     * {@inheritDoc}
     */
    public BulkAdder newBulkAdder() {
        return new BulkAdder();
    }

    /**
     * {@inheritDoc}
     */
    public DeltaCursor newCursor() {
        return new DeltaCursor();
    }

        /**
     * Return a new initCursor that can iterate over this data structure.
     * The initCursor will be positioned to the index {@code offset} and will
     * iterate over {@code length} elements.
     */
    public final DeltaCursor cursorFor(long offset, long length) {
        return initCursor(offset, length, cursors.acquire());
    }

    /**
     * Reposition an existing initCursor and return it.
     * The initCursor will be positioned to the index {@code offset} and will
     * iterate over {@code length} elements.
     * The return value is always {@code == reuse}.
     */
    public final DeltaCursor initCursor(long offset, long length, DeltaCursor reuse) {
        reuse.init(offset, length);
        return reuse;
    }

    /**
     * Allocated a certain amount of memory in the internal pages,
     * repositions the provided {@link BulkAdder} {@code into} to point to this region
     * and return the start offset where the allocation did happen.
     * this method is thread-safe and can be used to allocate something like
     * thread-local slabs of memory. Allocated slabs must be used fully without fragmentation.
     */
    public final long allocate(long numberOfElements, BulkAdder into) {
        long intoIndex = allocIdx.getAndAdd(numberOfElements);
        into.init(intoIndex, numberOfElements);
        return intoIndex;
    }

    /**
     * Skip a region of {@code numberOfElements} that will not be allocated.
     */
    public final void skipAllocationRegion(long numberOfElements) {
        allocIdx.addAndGet(numberOfElements);
    }

    public final void release() {
        cursors.close();
        pages = null;
    }

    public DeltaCursor deltaCursor(long offset) {
        DeltaCursor cursor = cursors.acquire();
        return cursor.init(offset);
    }

    public void returnCursor(DeltaCursor cursor) {
        cursors.release(cursor);
    }

    @Override
    protected byte[] newPage() {
        return new byte[pageSize];
    }

    private abstract class BaseCursor {

        public byte[] array;
        public int offset;
        public int limit;

        private long from;
        private long to;
        private long size;
        private int fromPage;
        private int toPage;
        private int currentPage;

        void init(long fromIndex, long length) {
            array = null;
            from = fromIndex;
            to = fromIndex + length;
            size = length;
            fromPage = pageIndex(fromIndex);
            toPage = pageIndex(to - 1);
            currentPage = fromPage - 1;
        }

        public final boolean next() {
            if (!setNext(++currentPage, fromPage, toPage)) {
                array = null;
                return false;
            }
            return true;
        }

        private boolean setNext(int current, int from, int to) {
            if (current > to) {
                return false;
            }
            setNextInRange(current, from, to);
            return true;
        }

        private void setNextInRange(int current, int from, int to) {
            if (current == from) {
                loadFirst(current);
            } else if (current < to) {
                loadMiddle(current);
            } else if (current == to) {
                loadLast(current);
            }
        }

        private void loadFirst(int current) {
            array = pages[current];
            offset = indexInPage(from);
            int length = (int) Math.min(pageSize - offset, size);
            limit = offset + length;
        }

        private void loadMiddle(int current) {
            array = pages[current];
            offset = 0;
            limit = offset + pageSize;
        }

        private void loadLast(int current) {
            array = pages[current];
            offset = 0;
            int length = indexInPage(to - 1) + 1;
            limit = offset + length;
        }
    }

    public final class BulkAdder extends BaseCursor {

        @Override
        public final void init(long fromIndex, long length) {
            grow(fromIndex + length);
            super.init(fromIndex, length);
            next();
        }

        public void addUnsignedInt(int i) {
            if (limit - offset >= 4) {
                quickAddUnsignedInt(i);
            } else {
                slowAddUnsignedInt(i);
            }
        }

        public void addVLong(long i) {
            if (limit - offset >= 9) {
                quickAddVLong(i);
            } else {
                slowAddVLong(i);
            }
        }

        private void quickAddUnsignedInt(int i) {
            int offset = this.offset;
            byte[] array = this.array;
            array[offset++] = (byte) (i >>> 24);
            array[offset++] = (byte) (i >>> 16);
            array[offset++] = (byte) (i >>> 8);
            array[offset++] = (byte) (i);
            this.offset = offset;
        }

        private void slowAddUnsignedInt(int i) {
            byte[] array = this.array;
            int offset = this.offset;
            switch (limit - offset) {
                case 0:
                    if (!next()) {
                        break;
                    }
                    quickAddUnsignedInt(i);
                    break;
                case 1:
                    array[offset++] = (byte) (i >>> 24);
                    if (!next()) {
                        break;
                    }
                    array = this.array;
                    offset = this.offset;
                    array[offset++] = (byte) (i >>> 16);
                    array[offset++] = (byte) (i >>> 8);
                    array[offset++] = (byte) (i);
                    break;
                case 2:
                    array[offset++] = (byte) (i >>> 24);
                    array[offset++] = (byte) (i >>> 16);
                    if (!next()) {
                        break;
                    }
                    array = this.array;
                    offset = this.offset;
                    array[offset++] = (byte) (i >>> 8);
                    array[offset++] = (byte) (i);
                    break;
                case 3:
                    array[offset++] = (byte) (i >>> 24);
                    array[offset++] = (byte) (i >>> 16);
                    array[offset++] = (byte) (i >>> 8);
                    if (!next()) {
                        break;
                    }
                    array = this.array;
                    offset = this.offset;
                    array[offset++] = (byte) (i);
                    break;
                default:
                    throw new IllegalArgumentException("invalid boundaries");
            }
            this.offset = offset;
        }

        private void quickAddVLong(long i) {
            int offset = this.offset;
            byte[] array = this.array;

            while ((i & ~0x7FL) != 0L) {
                array[offset++] = (byte) ((i & 0x7FL) | 0x80L);
                i >>>= 7;
            }
            array[offset++] = (byte) i;

            this.offset = offset;
        }

        private void slowAddVLong(long i) {
            int offset = this.offset;
            int limit = this.limit;
            byte[] array = this.array;

            while ((i & ~0x7FL) != 0) {
                if (offset >= limit) {
                    if (!next()) {
                        return;
                    }
                    array = this.array;
                    offset = this.offset;
                    limit = this.limit;
                } else {
                    array[offset++] = (byte) ((i & 0x7FL) | 0x80L);
                    i >>>= 7;
                }
            }

            this.offset = offset;
            if (offset >= limit) {
                if (!next()) {
                    return;
                }
            }

            this.array[this.offset++] = (byte) i;
        }
    }

    public final class DeltaCursor extends BaseCursor {
        private int currentTarget;
        private int maxTargets;
        private long delta;

        DeltaCursor init(long fromIndex) {
            super.init(fromIndex, capacity);
            next();

            currentTarget = 0;
            delta = 0;
            if (limit - offset >= 4) {
                initLength(array, offset);
            } else {
                initLengthSlow();
            }

            return this;
        }

        public long getVLong() {
            if (currentTarget++ >= maxTargets) {
                return -1L;
            }
            return delta = getVLong0();
        }

        private long getVLong0() {
            if (limit - offset >= 9) {
                return getVLong(array, offset);
            }
            return slowGetVLong();
        }

        private void initLength(byte[] array, int offset) {
            this.maxTargets = ((array[offset++] & 0xFF) << 24) |
                    ((array[offset++] & 0xFF) << 16) |
                    ((array[offset++] & 0xFF) << 8) |
                    (array[offset++] & 0xFF);
            this.offset = offset;
        }

        private void initLengthSlow() {
            int offset = this.offset;
            int limit = this.limit;
            byte[] page1 = this.array;

            if (!next()) {
                return;
            }

            byte[] page2 = this.array;
            int offset2 = this.offset;

            switch (limit - offset) {
                case 0:
                    initLength(page2, offset2);
                    return;

                case 1:
                    this.maxTargets = ((page1[offset] & 0xFF) << 24) |
                            ((page2[offset2++] & 0xFF) << 16) |
                            ((page2[offset2++] & 0xFF) << 8) |
                            (page2[offset2++] & 0xFF);
                    break;

                case 2:
                    this.maxTargets = ((page1[offset++] & 0xFF) << 24) |
                            ((page1[offset] & 0xFF) << 16) |
                            ((page2[offset2++] & 0xFF) << 8) |
                            (page2[offset2++] & 0xFF);
                    break;

                case 3:
                    this.maxTargets = ((page1[offset++] & 0xFF) << 24) |
                            ((page1[offset++] & 0xFF) << 16) |
                            ((page1[offset] & 0xFF) << 8) |
                            (page2[offset2++] & 0xFF);
                    break;

                default:
                    throw new IllegalArgumentException("invalid boundary");
            }

            this.offset = offset2;
        }

        private long getVLong(byte[] page, int offset) {
            byte b = page[offset++];
            long i = b & 0x7F;
            for (int shift = 7; (b & 0x80) != 0; shift += 7) {
                b = page[offset++];
                i |= (b & 0x7FL) << shift;
            }
            this.offset = offset;
            return i + delta;
        }

        private long slowGetVLong() {
            int diff = limit - offset;
            if (diff == 0) {
                if (!next()) {
                    return -1L;
                }
                return getVLong(this.array, this.offset);
            }

            byte[] array = this.array;
            int offset = this.offset;

            byte b = array[offset++];
            long i = b & 0x7F;
            for (int shift = 7; (b & 0x80) != 0; shift += 7) {
                if (--diff == 0) {
                    if (!next()) {
                        return -1;
                    }
                    array = this.array;
                    offset = this.offset;
                }
                b = array[offset++];
                i |= (b & 0x7FL) << shift;
            }
            this.offset = offset;
            return i + delta;
        }
    }
}
