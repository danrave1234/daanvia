package net.drave.atrdiagnostics;
import org.junit.Test;
public class TransportRegressionTest {
    @Test public void framing()throws Exception{PxcTest.main(new String[0]);}
    @Test public void qr()throws Exception{QrTest.main(new String[0]);}
    @Test public void media()throws Exception{MediaWireTest.main(new String[0]);}
    @Test public void ports()throws Exception{CallbackPortsTest.main(new String[0]);}
    @Test public void heartbeat()throws Exception{KeepaliveTest.main(new String[0]);}
}
