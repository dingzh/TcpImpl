public class RingBuffer {
    private final int capacity;
    private final byte[] buffer;
    private int head;
    private int tail;  // tail point to first empty byte
    private int size;

    public RingBuffer(int buffer_size) {
        capacity = Math.min(4 * 1024, buffer_size); // at least 8k buffer size
        buffer = new byte[capacity];
        head = tail = size = 0;
    }

    public int write(byte[] in, int pos, int len) {
        int writeIn = Math.min(len, capacity - size);

        size += writeIn;
        for (int i = pos; i < pos + writeIn; ++i) {
            buffer[tail++] = in[i];
            if (tail == capacity) tail = 0;
        }

        return writeIn;
    }

    public int read(byte[] out, int pos, int len) {
        int readOut = Math.min(len, size);

        size -= readOut;
        for (int i = pos; i < pos + readOut; ++i) {
            out[i] = buffer[head++];
            if (head == capacity) head = 0;
        }

        return readOut;
    }

    public int avail() {
        return size;
    }

    public int free() {
        return capacity - size;
    }
}

