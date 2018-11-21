import com.sun.tools.internal.xjc.reader.Ring;

import java.lang.reflect.Method;
import java.util.LinkedList;
import java.util.NoSuchElementException;
import java.util.Queue;

import static java.lang.System.exit;

/**
 * <p>Title: CPSC 433/533 Programming Assignment</p>
 *
 * <p>Description: Fishnet socket implementation</p>
 *
 * <p>Copyright: Copyright (c) 2006</p>
 *
 * <p>Company: Yale University</p>
 *
 * @author Hao Wang
 * @version 1.0
 */

public class TCPSock {
    // TCP socket states
    enum State {
        // protocol states
        WAIT_BIND,   // socket create, not bind yet
        BOUND,
        LISTEN,
        SYN_SENT,    // sent syn, waiting for ack
        ESTABLISHED_WRITE, // create data buffer when connection established
        ESTABLISHED_READ,  // since this is one-way stream
        SHUTDOWN,    // close requested, FIN not sent (due to unsent data in queue)
        CLOSED       // socket is finished
    }

    private static int DATA_BUFFER_SIZE = 64 * 1024; // 64KB buffer

    private Method mRetransmit = null;
    private Method mReconnect = null;
    private TCPManager tcpMan;
    private State state = State.WAIT_BIND;

    private int localPort = 0;
    private int destAddr = 0;
    private int destPort = 0;

    private int seqNum = 0;
    private int window = 1;
    private int backlog = 0;
    private Queue<int[]> synQueue = null;
    private RingBuffer rb = null;     // one-way stream only one buffer needed

    private long timeout = 100;
    private Transport pendingSegment = null;

    private int RTT;

    public TCPSock(TCPManager tcpMan) {
        this.tcpMan = tcpMan;

        Class[] paramReconnect = new Class[2];
        paramReconnect[0] = int.class;
        paramReconnect[1] = int.class;
        Class[] paramRetransmit = new Class[1];
        paramRetransmit[0] = int.class;

        try {
            mRetransmit = this.getClass().getMethod("retransmit", paramRetransmit);
            mReconnect = this.getClass().getMethod("reconnect", paramReconnect);
        } catch (NoSuchMethodException ex) {
            tcpMan.logError(ex.toString());
            exit(1);
        }
    }

    /*
     * The following are the socket APIs of TCP transport service.
     * All APIs are NON-BLOCKING.
     */

    /**
     * Bind a socket to a local port
     *
     * @param localPort int local port number to bind the socket to
     * @return int 0 on success, -1 otherwise
     */
    public int bind(int localPort) {
        int ret = -1;
        if (state == State.WAIT_BIND) {
            // delegate TCPManager to bind port
            ret = tcpMan.bindLocal(this, localPort);
            if (ret == 0) {
                this.localPort = localPort;
                this.state = State.BOUND;
            }
        }
        return ret;
    }

    /**
     * Listen for connections on a socket
     * @param backlog int Maximum number of pending connections
     * @return int 0 on success, -1 otherwise
     */
    public int listen(int backlog) {
        if (state != State.BOUND) {
            return -1;
        }

        state = State.LISTEN;
        synQueue = new LinkedList<>();
        this.backlog = backlog;

        return 0;
    }

    /**
     * Accept a connection on a socket
     *
     * @return TCPSock The first established connection on the request queue
     */
    public TCPSock accept() {
        if (state != State.LISTEN || synQueue.isEmpty()) {
            return null;
        }
        tcpMan.logOutput("Calling accept.");

        TCPSock client;
        int[] remote;
        while (true) {
            try {
                remote = synQueue.remove();
                client = tcpMan.bindRemote(localPort, remote[0], remote[1]);
            } catch (NoSuchElementException ex) {
                return null;
            }
            if (client != null) {
                client.state = State.ESTABLISHED_READ;
                client.rb = new RingBuffer(DATA_BUFFER_SIZE);
                client.localPort = localPort;
                client.destAddr = remote[0];
                client.destPort = remote[1];
                client.seqNum = remote[2] + 1;  // received syn, expecting next
                client.window = 1;
                tcpMan.sendAck(localPort, remote[0], remote[1], client.window, client.seqNum);
                return client;
            }
        }
    }

    public boolean isConnectionPending() {
        return (state == State.SYN_SENT);
    }

    public boolean isClosed() {
        return (state == State.CLOSED);
    }

    public boolean isListening() {
        return (state == State.LISTEN);
    }

    public boolean isConnected() {
        return (state == State.ESTABLISHED_READ || state == State.ESTABLISHED_WRITE);
    }

    public boolean isClosurePending() {
        return (state == State.SHUTDOWN);
    }

    /**
     * Initiate connection to a remote socket
     *
     * @param destAddr int Destination node address
     * @param destPort int Destination port
     * @return int 0 on success, -1 otherwise
     */
    public int connect(int destAddr, int destPort) {
        tcpMan.logOutput("Connect to " + destAddr + ":" + destPort);
        if (state != State.BOUND) {
            return -1;
        }

        // todo set up seqNum
        seqNum = 0;
        this.destAddr = destAddr;
        this.destPort = destPort;
        state = State.SYN_SENT;
        tcpMan.sendSyn(localPort, destAddr, destPort, seqNum);

        // get method to call if connect request timeout


        Object[] params = new Object[2];
        params[0] = destAddr;
        params[1] = destPort;

        Callback cb = new Callback(mReconnect, this, params);
        tcpMan.addTimer(timeout * 10, cb);

        return -1;
    }

    public void reconnect(int destAddr, int destPort) {
        if (state == State.SYN_SENT) {
            tcpMan.logOutput("Reconnect to " + destAddr + ":" + destPort);
            tcpMan.sendSyn(localPort, destAddr, destPort, seqNum);

            Object[] params = new Object[2];
            params[0] = destAddr;
            params[1] = destPort;

            Callback cb = new Callback(mReconnect, this, params);
            tcpMan.addTimer(timeout * 10, cb);
        }
    }

    /**
     * Initiate closure of a connection (graceful shutdown)
     */
    public void close() {
        // TODO send FIN
        switch (state) {
            case ESTABLISHED_WRITE:
                // will send fin when all data sent
                state = State.SHUTDOWN;
                break;

            case ESTABLISHED_READ:
                tcpMan.sendFin(localPort, destAddr, destPort);
                state = State.CLOSED;
                rb = null;
                break;

            case LISTEN:
                state = State.CLOSED;
                synQueue = null;
                break;

            default:
                state = State.CLOSED;
        }
    }

    /**
     * Release a connection immediately (abortive shutdown)
     */
    public void release() {
        tcpMan.sendFin(localPort, destAddr, destPort);
        state = State.CLOSED;
        rb = null;
    }

    /**
     * Write to the socket up to len bytes from the buffer buf starting at
     * position pos.
     *
     * @param buf byte[] the buffer to write from
     * @param pos int starting position in buffer
     * @param len int number of bytes to write
     * @return int on success, the number of bytes written, which may be smaller
     *             than len; on failure, -1
     */
    public int write(byte[] buf, int pos, int len) {
        int ret = -1;
        if (state == State.ESTABLISHED_WRITE) {
            ret = rb.write(buf, pos, len);
            sendData(); // invoke to send new data
        }

        return ret;
    }

    /**
     * Read from the socket up to len bytes into the buffer buf starting at
     * position pos.
     *
     * @param buf byte[] the buffer
     * @param pos int starting position in buffer
     * @param len int number of bytes to read
     * @return int on success, the number of bytes read, which may be smaller
     *             than len; on failure, -1
     */
    public int read(byte[] buf, int pos, int len) {
        int ret = -1;
        if (state == State.ESTABLISHED_READ) {
            ret = rb.read(buf, pos, len);
        }

        return ret;
    }

    /*
     * End of socket API
     */

    void sendData() {
        if (pendingSegment != null) return ;
        // check if more data to send
        int len = rb.avail();
        if (len == 0) {
            if (state == State.SHUTDOWN) state = State.CLOSED;
            return ;
        }

        len = Math.min(len, Transport.MAX_PAYLOAD_SIZE);
        byte[] data = new byte[len];
        rb.read(data, 0, len);
        Transport transport = new Transport(localPort, destPort, Transport.DATA, window, seqNum, data);
        pendingSegment = transport;
        tcpMan.sendData(destAddr, pendingSegment.pack());
        tcpMan.logOutput("" + seqNum + "\t" + data[0] + " -> " + data[len-1]);
        // get method to call if connect request timeout
        Object[] params = new Object[1];
        params[0] = seqNum;

        Callback cb = new Callback(mRetransmit, this, params);
        tcpMan.addTimer(timeout, cb);
        // TODO add timer for RTT
        // TODO make sure packet size is large enough
    }

    public void retransmit(int seqNum) {
        if (state == State.ESTABLISHED_WRITE && pendingSegment != null && pendingSegment.getSeqNum() == seqNum) {
            byte[] data = pendingSegment.getPayload();
            tcpMan.logOutput("" + seqNum + "\t" + data[0] + " -> " + data[data.length-1] + " R");
            tcpMan.sendData(destAddr, pendingSegment.pack());

            Object[] params = new Object[1];
            params[0] = seqNum;

            Callback cb = new Callback(mRetransmit, this, params);
            tcpMan.addTimer(timeout, cb);
        }
    }

    void onACK(int ack) {
        // TODO impplement sliding window
        if (pendingSegment == null) return ; // duplicate ack
        int expectSeqNum = seqNum + pendingSegment.getPayload().length;
        tcpMan.logOutput("ACK\t" + ack);
        if (ack == expectSeqNum ) {
            tcpMan.logOutput(TCPManager.ACK_1_SYMBOL);
            seqNum = expectSeqNum;
            pendingSegment = null;
            sendData();
        } else {
            tcpMan.logOutput(TCPManager.ACK_2_SYMBOL);
            // seqNum does not match, meaning data corrupted, need to retransmit
            retransmit(seqNum);
        }
    }


    void onSYN(int srcAddr, int srcPort, int seqNum) {
        // actually will have to be LISTEN, tcpMan has checked before calling
        if (state == State.LISTEN) { // server socket
            // put into backlog queue if space avail
            if (backlog >= synQueue.size()) {
                int[] src = new int[3];
                src[0] = srcAddr;
                src[1] = srcPort;
                src[2] = seqNum;
                synQueue.add(src);
            } else {
                // nothing, the client will request again
            }
        }
    }

    void onAckSyn() {
        tcpMan.sendAck(localPort, destAddr, destPort, window, seqNum);
    }

    // TODO need a receive method for tcp manager to call,
    // 1. check state
    // 2. copy data to internal buffer
    // 3. send ACK
    // 4. cirrcular buffer size 64KB
    // 5. in case of overflow drop and send ACK, we have flow control
    public void onData(Transport transport) {
        // i'm the receiver, which is the server
        if (seqNum == transport.getSeqNum()) {
            byte[] data = transport.getPayload();
            if (data.length <= rb.free()) {
                tcpMan.logOutput("Accept\t" + data[0] + " -> " + data[data.length-1]);
                rb.write(data, 0, data.length);
                seqNum += data.length;
                tcpMan.sendAck(localPort, destAddr, destPort, window, seqNum);
            } else {
                // todo send window 0 to choke
            }
        } else if(seqNum > transport.getSeqNum()) {
            // assume last ack is lost
            tcpMan.logOutput(TCPManager.RETRAN_DUP_SYMBOL);
            tcpMan.sendAck(localPort, destAddr, destPort, window,
                    transport.getSeqNum() + transport.getPayload().length);
        }
    }

    public void onFIN() {
        switch (state) {
            case SYN_SENT:          // connection refused
            case ESTABLISHED_READ:  // all segments are acked, sender makes sure of that before FIN
            case ESTABLISHED_WRITE: // the remote refused to receive
                state = State.CLOSED;
                break;
        }
    }

    public void onSynAck(Transport transport) {
        if (seqNum + 1 == transport.getSeqNum()) {
            state = State.ESTABLISHED_WRITE;
            rb = new RingBuffer(DATA_BUFFER_SIZE);
            ++seqNum;
            tcpMan.logOutput("Connection established.");
        }
    }
}

