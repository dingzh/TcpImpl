import javafx.util.Pair;

import java.util.HashMap;

/**
 * <p>Title: CPSC 433/533 Programming Assignment</p>
 *
 * <p>Description: Fishnet TCP manager</p>
 *
 * <p>Copyright: Copyright (c) 2006</p>
 *
 * <p>Company: Yale University</p>
 *
 * @author Hao Wang
 * @version 1.0
 */
public class TCPManager {
    static String SYN_SYMBOL = "S";
    static String FIN_SYMBOL = "F";
    static String DATA_SYMBOL = ".";
    static String RETRAN_DUP_SYMBOL = "!";
    static String ACK_1_SYMBOL = ":";
    static String ACK_2_SYMBOL = "?";

    private Node node;
    private int addr;
    private Manager manager;
    private HashMap<Integer, HashMap<Pair<Integer, Integer>, TCPSock>> portMapping;

    private static final byte dummy[] = new byte[0];

    public TCPManager(Node node, int addr, Manager manager) {
        this.node = node;
        this.addr = addr;
        this.manager = manager;
        this.portMapping = new HashMap<>();
    }

    /**
     * Start this TCP manager
     */
    public void start() {

    }

    /*
     * Begin socket API
     */

    /**
     * Create a socket
     *
     * @return TCPSock the newly created socket, which is not yet bound to
     *                 a local port
     */
    public TCPSock socket() {
        // TODO create socket, set up state, put into socket list for look up
        TCPSock socket = new TCPSock(this);

        return socket;
    }

    /*
     * End Socket API
     */

     // TODO onReceiveSegment
    void onReceivePacket(Packet packet) {
        // extract value for multiplexing, find the right socket
        // check server socket and connection socket can exist
        Transport transport = Transport.unpack(packet.getPayload());
        int destPort = transport.getDestPort();
        int destAddr = packet.getDest();
        int srcAddr = packet.getSrc();
        int srcPort = transport.getSrcPort();

        TCPSock socket = matchSocket(destAddr, destPort, srcAddr, srcPort);

        if (socket == null) { // no matching result, send FIN, connection refused
            sendFin(destPort, srcAddr, srcPort);
            return ;
        }

        // check packet content and match socket state
        switch (transport.getType()) {
            case Transport.SYN:
                if (socket.isListening()) {
                    logOutput(SYN_SYMBOL);
                    socket.onSYN(srcAddr, srcPort, transport.getSeqNum());
                } else if(socket.isConnected()) {
                    socket.onAckSyn();
                } else {
                    logOutput("send FIN");
                    sendFin(destPort, srcAddr, srcPort);
                }
                break;

            case Transport.ACK:
                if (socket.isConnectionPending()) {
                    // waiting to connect
                    socket.onSynAck(transport);
                } else if (socket.isConnected()) {
                    // ack on data
                    socket.onACK(transport.getSeqNum());
                }

                break;

            case Transport.FIN:
                if (socket.isConnected() || socket.isConnectionPending()) {
                    logOutput(FIN_SYMBOL);
                    socket.onFIN();
                }
                break;

            case Transport.DATA:
                if (socket.isConnected()) {
                    logOutput(DATA_SYMBOL);
                    socket.onData(transport);
                } else {
                    logOutput("Data packet to non receiving socket.");
                }
                break;

            default: // unrecognized type
        }
    }


    // best effort match
    TCPSock matchSocket(int destAddr, int desPort,  int srcAddr, int srcPort ) {
//        logOutput("destAddr " + destAddr + " destPort " + desPort + " srcAddr " + srcAddr + " srcPort " + srcPort);
        if (destAddr != addr) return null;

        HashMap<Pair<Integer, Integer>, TCPSock> socks = portMapping.get(desPort);
        Pair<Integer, Integer> remote = new Pair<>(srcAddr, srcPort);
        if (socks != null && socks.containsKey(remote)) {
            return socks.get(remote);
        }

        Pair<Integer, Integer> server = new Pair<>(-1, -1);
        if (socks != null && socks.containsKey(server)) {
            return socks.get(server);
        }

        return null;
    }



    // TODO onWrite
    /**
     * Bind port to a socket
     *
     * @return TCPSock the newly created socket, which is not yet bound to
     *                 a local port
     */
    int bindLocal(TCPSock sock, int localPort) {
        if (portMapping.containsKey(localPort)) return -1;

        HashMap<Pair<Integer, Integer>, TCPSock> val = new HashMap<>();
        val.put(new Pair<>(-1, -1), sock);
        portMapping.put(localPort, val);
        logOutput("port bind to " + localPort);
        return 0;
    }

    TCPSock bindRemote(int localPort, int remoteAddr, int remotePort) {
        // check if connect exist already
        HashMap<Pair<Integer, Integer>, TCPSock> socks = portMapping.get(localPort);
        Pair<Integer, Integer> remote = new Pair<>(remoteAddr, remotePort);
        if (socks != null && !socks.containsKey(remote)) {
            TCPSock socket = socket();
            socks.put(remote, socket);
            return socket;
        }

        return null;
    }

    void sendSyn(int srcPort, int destAddr, int destPort, int seqNum) {
        Transport syn = new Transport(srcPort, destPort, Transport.SYN, 0, seqNum, dummy);
        node.sendSegment(addr, destAddr, Protocol.TRANSPORT_PKT, syn.pack());
    }

    void sendFin(int srcPort, int destAddr, int destPort) {
        Transport fin = new Transport(srcPort, destPort, Transport.FIN, 0, 0, dummy);
        node.sendSegment(addr, destAddr, Protocol.TRANSPORT_PKT, fin.pack());
    }

    void sendAck(int srcPort, int destAddr, int destPort, int window, int seqNmu) {
        Transport ack = new Transport(srcPort, destPort, Transport.ACK, window, seqNmu, dummy);
        node.sendSegment(addr, destAddr, Protocol.TRANSPORT_PKT, ack.pack());
    }

    void sendData(int destAddr, byte[] transportPacked) {
        node.sendSegment(addr, destAddr, Protocol.TRANSPORT_PKT, transportPacked);
    }

    void addTimer(long deltaT, Callback cb) {
        manager.addTimer(addr, deltaT, cb);
    }

    void logOutput(String output) {
        node.logOutput(output);
    }

    void logError(String output) {
        node.logError(output);
    }

    void socketClose() {
        // TODO remove socket for match
    }
}
