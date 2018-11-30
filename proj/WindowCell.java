public class WindowCell implements Comparable<WindowCell> {
    public long time;
    public int seqNum;
    public int dataLength;
    public Transport transport;
//    public boolean calcRTT;

    public WindowCell(long time, int seqNum, int dataLength, Transport transport) {
        this.time = time;
        this.seqNum = seqNum;
        this.dataLength = dataLength;
        this.transport = transport;
//        calcRTT = true;
    }

    @Override
    public int compareTo(WindowCell o) {
        return Integer.compare(seqNum, o.seqNum);
    }
}
