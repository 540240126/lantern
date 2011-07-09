package org.lantern;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import javax.net.SocketFactory;

import net.sf.ehcache.store.chm.ConcurrentHashMap;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.RandomUtils;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMessage;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpRequestEncoder;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.ConnectionListener;
import org.jivesoftware.smack.Roster;
import org.jivesoftware.smack.RosterEntry;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.json.simple.JSONArray;
import org.lastbamboo.common.offer.answer.NoAnswerException;
import org.lastbamboo.common.p2p.P2PClient;
import org.lastbamboo.common.stun.client.PublicIpAddress;
import org.littleshoot.proxy.ProxyUtils;
import org.littleshoot.util.ByteBufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;
import com.maxmind.geoip.Country;
import com.maxmind.geoip.LookupService;

/**
 * Utility methods for use with Lantern.
 */
public class LanternUtils {

    private static final Logger LOG = 
        LoggerFactory.getLogger(LanternUtils.class);
    
    private static String MAC_ADDRESS;
    
    private static final File CONFIG_DIR = 
        new File(System.getProperty("user.home"), ".lantern");
    
    private static final File PROPS_FILE =
        new File(CONFIG_DIR, "lantern.properties");
    
    private static final Properties PROPS = new Properties();
    
    static {
        if (!CONFIG_DIR.isDirectory()) {
            if (!CONFIG_DIR.mkdirs()) {
                LOG.error("Could not make config directory at: "+CONFIG_DIR);
            }
        }
        if (!PROPS_FILE.isFile()) {
            try {
                if (!PROPS_FILE.createNewFile()) {
                    LOG.error("Could not create props file!!");
                }
            } catch (final IOException e) {
                LOG.error("Could not create props file!!", e);
            }
        }
        
        InputStream is = null;
        try {
            is = new FileInputStream(PROPS_FILE);
            PROPS.load(is);
        } catch (final IOException e) {
            LOG.error("Error loading props file: "+PROPS_FILE, e);
        } finally {
            IOUtils.closeQuietly(is);
        }
    }
    
    public static final ClientSocketChannelFactory clientSocketChannelFactory =
        new NioClientSocketChannelFactory(
            Executors.newCachedThreadPool(),
            Executors.newCachedThreadPool());
    
    /**
     * Censored country codes, in order of population.
     */
    private static final Collection<String> CENSORED =
        Sets.newHashSet(
            // Asia 
            "CN",
            "VN",
            "MM",
            //Mideast: 
            "IR", 
            "BH", 
            "YE", 
            "SA", 
            "SY",
            //Eurasia: 
            "UZ", 
            "TM",
            //Africa: 
            "ET", 
            "ER",
            // LAC: 
            "CU");

    // These country codes have US export restrictions, and therefore cannot
    // access App Engine sites.
    private static final Collection<String> EXPORT_RESTRICTED =
        Sets.newHashSet(
            "SY");
    
    private static final File UNZIPPED = new File("GeoIP.dat");
    
    private static LookupService lookupService;

    private static String countryCode;

    static {
        if (!UNZIPPED.isFile())  {
            final File file = new File("GeoIP.dat.gz");
            GZIPInputStream is = null;
            OutputStream os = null;
            try {
                is = new GZIPInputStream(new FileInputStream(file));
                os = new FileOutputStream(UNZIPPED);
                IOUtils.copy(is, os);
            } catch (final IOException e) {
                LOG.warn("Error expanding file?", e);
            } finally {
                IOUtils.closeQuietly(is);
                IOUtils.closeQuietly(os);
            }
        }
        try {
            lookupService = new LookupService(UNZIPPED, 
                    LookupService.GEOIP_MEMORY_CACHE);
        } catch (final IOException e) {
            lookupService = null;
        }
    }
    
    public static String countryCode() {
        if (StringUtils.isNotBlank(countryCode)) {
            return countryCode;
        }
        
        countryCode = countryCode(PublicIpAddress.getPublicIpAddress());
        return countryCode;
    }
    
    public static String countryCode(final InetAddress address) {
        final Country country = lookupService.getCountry(address);
        LOG.info("Country is: {}", country.getName());
        return country.getCode().trim();
    }
    
    public static boolean isCensored() {
        return isCensored(PublicIpAddress.getPublicIpAddress());
    }
    
    public static boolean isCensored(final InetAddress address) {
        return isMatch(address, CENSORED);
    }

    public static boolean isCensored(final String address) throws IOException {
        return isCensored(InetAddress.getByName(address));
    }
    
    public static boolean isExportRestricted() {
        return isExportRestricted(PublicIpAddress.getPublicIpAddress());
    }
    
    public static boolean isExportRestricted(final InetAddress address) { 
        return isMatch(address, EXPORT_RESTRICTED);
    }

    public static boolean isExportRestricted(final String address) 
        throws IOException {
        return isExportRestricted(InetAddress.getByName(address));
    }
    
    public static boolean isMatch(final InetAddress address, 
        final Collection<String> countries) { 
        final Country country = lookupService.getCountry(address);
        LOG.info("Country is: {}", country);
        countryCode = country.getCode().trim();
        return countries.contains(country.getCode().trim());
    }
    
    /**
     * Helper method that ensures all written requests are properly recorded.
     * 
     * @param request The request.
     */
    public static void writeRequest(final Queue<HttpRequest> httpRequests,
        final HttpRequest request, final ChannelFuture cf) {
        httpRequests.add(request);
        LOG.info("Writing request: {}", request);
        LanternUtils.genericWrite(request, cf);
    }
    
    public static void genericWrite(final Object message, 
        final ChannelFuture future) {
        final Channel ch = future.getChannel();
        if (ch.isConnected()) {
            ch.write(message);
        } else {
            future.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture cf) 
                    throws Exception {
                    if (cf.isSuccess()) {
                        ch.write(message);
                    }
                }
            });
        }
    }
    
    public static Socket openRawOutgoingPeerSocket(
        final Channel browserToProxyChannel,
        final URI uri, final ChannelHandlerContext ctx,
        final ProxyStatusListener proxyStatusListener,
        final P2PClient p2pClient,
        final Map<URI, AtomicInteger> peerFailureCount) throws IOException {
        return openOutgoingPeerSocket(browserToProxyChannel, uri, ctx, 
            proxyStatusListener, p2pClient, peerFailureCount, true);
    }
    
    public static Socket openOutgoingPeerSocket(
        final Channel browserToProxyChannel,
        final URI uri, final ChannelHandlerContext ctx,
        final ProxyStatusListener proxyStatusListener,
        final P2PClient p2pClient,
        final Map<URI, AtomicInteger> peerFailureCount) throws IOException {
        return openOutgoingPeerSocket(browserToProxyChannel, uri, ctx, 
            proxyStatusListener, p2pClient, peerFailureCount, false);
    }
    
    private static Socket openOutgoingPeerSocket(
        final Channel browserToProxyChannel,
        final URI uri, final ChannelHandlerContext ctx,
        final ProxyStatusListener proxyStatusListener,
        final P2PClient p2pClient,
        final Map<URI, AtomicInteger> peerFailureCount,
        final boolean raw) throws IOException {
        
        // This ensures we won't read any messages before we've successfully
        // created the socket.
        browserToProxyChannel.setReadable(false);

        // Start the connection attempt.
        try {
            LOG.info("Creating a new socket to {}", uri);
            final Socket sock;
            if (raw) {
                sock = p2pClient.newRawSocket(uri);
            } else {
                sock = p2pClient.newSocket(uri);
            }
            LOG.info("Got outgoing peer socket: {}", sock);
            browserToProxyChannel.setReadable(true);
            startReading(sock, browserToProxyChannel);
            return sock;
        } catch (final NoAnswerException nae) {
            // This is tricky, as it can mean two things. First, it can mean
            // the XMPP message was somehow lost. Second, it can also mean
            // the other side is actually not there and didn't respond as a
            // result.
            LOG.info("Did not get answer!! Closing channel from browser", nae);
            final AtomicInteger count = peerFailureCount.get(uri);
            if (count == null) {
                LOG.info("Incrementing failure count");
                peerFailureCount.put(uri, new AtomicInteger(0));
            }
            else if (count.incrementAndGet() > 5) {
                LOG.info("Got a bunch of failures in a row to this peer. " +
                    "Removing it.");
                
                // We still reset it back to zero. Note this all should 
                // ideally never happen, and we should be able to use the
                // XMPP presence alerts to determine if peers are still valid
                // or not.
                peerFailureCount.put(uri, new AtomicInteger(0));
                proxyStatusListener.onCouldNotConnectToPeer(uri);
            } 
            throw nae;
        } catch (final IOException ioe) {
            proxyStatusListener.onCouldNotConnectToPeer(uri);
            LOG.warn("Could not connect to peer", ioe);
            throw ioe;
        }
    }
    
    private static void startReading(final Socket sock, final Channel channel) {
        final Runnable runner = new Runnable() {

            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                long count = 0;
                int n = 0;
                try {
                    final InputStream is = sock.getInputStream();
                    while (-1 != (n = is.read(buffer))) {
                        //LOG.info("Writing response data: {}", new String(buffer, 0, n));
                        // We need to make a copy of the buffer here because
                        // the writes are asynchronous, so the bytes can
                        // otherwise get scrambled.
                        final ChannelBuffer buf =
                            ChannelBuffers.copiedBuffer(buffer, 0, n);
                        channel.write(buf);
                        count += n;
                    }
                    ProxyUtils.closeOnFlush(channel);

                } catch (final IOException e) {
                    LOG.info("Exception relaying peer data back to browser",e);
                    ProxyUtils.closeOnFlush(channel);
                    
                    // The other side probably just closed the connection!!
                    
                    //channel.close();
                    //proxyStatusListener.onError(peerUri);
                    
                }
            }
        };
        final Thread peerReadingThread = 
            new Thread(runner, "Peer-Data-Reading-Thread");
        peerReadingThread.setDaemon(true);
        peerReadingThread.start();
    }
    
    public static String getMacAddress() {
        if (MAC_ADDRESS != null) {
            return MAC_ADDRESS;
        }
        final Enumeration<NetworkInterface> nis;
        try {
            nis = NetworkInterface.getNetworkInterfaces();
        } catch (final SocketException e1) {
            throw new Error("Could not read network interfaces?");
        }
        while (nis.hasMoreElements()) {
            final NetworkInterface ni = nis.nextElement();
            try {
                if (!ni.isUp()) {
                    LOG.info("Ignoring interface that's not up: {}", 
                        ni.getDisplayName());
                    continue;
                }
                final byte[] mac = ni.getHardwareAddress();
                if (mac != null && mac.length > 0) {
                    LOG.info("Returning 'normal' MAC address");
                    MAC_ADDRESS = Base64.encodeBase64String(mac).trim();
                    return MAC_ADDRESS;
                }
            } catch (final SocketException e) {
                LOG.warn("Could not get MAC address?");
            }
        }
        try {
            LOG.warn("Returning custom MAC address");
            MAC_ADDRESS = Base64.encodeBase64String(
                InetAddress.getLocalHost().getAddress()) + 
                System.currentTimeMillis();
            return MAC_ADDRESS;
        } catch (final UnknownHostException e) {
            final byte[] bytes = new byte[24];
            new Random().nextBytes(bytes);
            return Base64.encodeBase64String(bytes);
        }
    }

    public static File configDir() {
        return CONFIG_DIR;
    }
    
    public static File propsFile() {
        return PROPS_FILE;
    }
    
    public static boolean isTransferEncodingChunked(final HttpMessage m) {
        List<String> chunked = m.getHeaders(HttpHeaders.Names.TRANSFER_ENCODING);
        if (chunked.isEmpty()) {
            return false;
        }

        for (String v: chunked) {
            if (v.equalsIgnoreCase(HttpHeaders.Values.CHUNKED)) {
                return true;
            }
        }
        return false;
    }

    public static JSONArray toJsonArray(final Collection<String> strs) {
        final JSONArray json = new JSONArray();
        synchronized (strs) {
            json.addAll(strs);
        }
        return json;
    }

    public static boolean isForceCensored() {
        final boolean force = 
            getBooleanProperty(LanternConstants.FORCE_CENSORED);
        LOG.info("Forcing proxy: "+force);
        return force;
    }

    public static void forceCensored() {
        setBooleanProperty(LanternConstants.FORCE_CENSORED, true);
    }

    private static void setBooleanProperty(final String key, 
        final boolean value) {
        PROPS.setProperty(key, String.valueOf(value));
    }

    private static boolean getBooleanProperty(final String key) {
        final String val = PROPS.getProperty(key);
        if (StringUtils.isBlank(val)) {
            return false;
        }
        LOG.info("Checking property: {}", val);
        return "true".equalsIgnoreCase(val.trim());
    }
    
    public static boolean isConfigured() {
        if (!PROPS_FILE.isFile()) {
            return false;
        }
        final Properties props = new Properties();
        InputStream is = null;
        try {
            is = new FileInputStream(PROPS_FILE);
            props.load(is);
            final String un = props.getProperty("google.user");
            final String pwd = props.getProperty("google.pwd");
            return (StringUtils.isNotBlank(un) && StringUtils.isNotBlank(pwd));
        } catch (final IOException e) {
            LOG.error("Error loading props file: "+PROPS_FILE, e);
        } finally {
            IOUtils.closeQuietly(is);
        }
        return false;
    }

    private static final String keyString = String.valueOf(RandomUtils.nextLong());
    public static String keyString() {
        return keyString;
    }
    
    
    public static Collection<RosterEntry> getRosterEntries(final String email,
        final String pwd, final int attempts) throws IOException {
        final XMPPConnection conn = 
            persistentXmppConnection(email, pwd, "lantern", attempts);
        final Roster roster = conn.getRoster();
        final Collection<RosterEntry> unordered = roster.getEntries();
        final Comparator<RosterEntry> comparator = new Comparator<RosterEntry>() {
            @Override
            public int compare(final RosterEntry re1, final RosterEntry re2) {
                return re1.getName().compareToIgnoreCase(re2.getName());
            }
        };
        final Collection<RosterEntry> entries = 
            new TreeSet<RosterEntry>(comparator);
        for (final RosterEntry entry : unordered) {
            final String name = entry.getName();
            if (StringUtils.isNotBlank(name)) {
                entries.add(entry);
            }
        }
        return entries;
    }
    
    private static final Map<String, XMPPConnection> xmppConnections = 
        new ConcurrentHashMap<String, XMPPConnection>();

    private static XMPPConnection persistentXmppConnection(final String username, 
            final String password, final String id) throws IOException {
        return persistentXmppConnection(username, password, id, 4);
    }
    
    private static XMPPConnection persistentXmppConnection(final String username, 
        final String password, final String id, final int attempts) throws IOException {
        final String key = username+password;
        if (xmppConnections.containsKey(key)) {
            final XMPPConnection conn = xmppConnections.get(key);
            if (conn.isAuthenticated() && conn.isConnected()) {
                LOG.info("Returning existing xmpp connection");
                return conn;
            } else {
                LOG.info("Removing stale connection");
                xmppConnections.remove(key);
            }
        }
        XMPPException exc = null;
        for (int i = 0; i < attempts; i++) {
            try {
                LOG.info("Attempting XMPP connection...");
                final XMPPConnection conn = 
                    singleXmppConnection(username, password, id);
                LOG.info("Created offerer");
                xmppConnections.put(key, conn);
                return conn;
            } catch (final XMPPException e) {
                final String msg = "Error creating XMPP connection";
                LOG.error(msg, e);
                exc = e;    
            }
            
            // Gradual backoff.
            try {
                Thread.sleep(i * 600);
            } catch (final InterruptedException e) {
                LOG.info("Interrupted?", e);
            }
        }
        if (exc != null) {
            throw new IOException("Could not log in!!", exc);
        }
        else {
            throw new IOException("Could not log in?");
        }
    }
    
    private static XMPPConnection singleXmppConnection(final String username, 
        final String password, final String id) throws XMPPException {
        final ConnectionConfiguration config = 
            new ConnectionConfiguration("talk.google.com", 5222, "gmail.com");
            //new ConnectionConfiguration(this.host, this.port, this.serviceName);
        config.setCompressionEnabled(true);
        config.setRosterLoadedAtLogin(true);
        config.setReconnectionAllowed(false);
        
        // TODO: This should probably be an SSLSocketFactory no??
        config.setSocketFactory(new SocketFactory() {
            
            @Override
            public Socket createSocket(final InetAddress host, final int port, 
                final InetAddress localHost, final int localPort) 
                throws IOException {
                // We ignore the local port binding.
                return createSocket(host, port);
            }
            
            @Override
            public Socket createSocket(final String host, final int port, 
                final InetAddress localHost, final int localPort)
                throws IOException, UnknownHostException {
                // We ignore the local port binding.
                return createSocket(host, port);
            }
            
            @Override
            public Socket createSocket(final InetAddress host, final int port) 
                throws IOException {
                LOG.info("Creating socket");
                final Socket sock = new Socket();
                sock.connect(new InetSocketAddress(host, port), 40000);
                LOG.info("Socket connected");
                return sock;
            }
            
            @Override
            public Socket createSocket(final String host, final int port) 
                throws IOException, UnknownHostException {
                LOG.info("Creating socket");
                return createSocket(InetAddress.getByName(host), port);
            }
        });
        
        return newConnection(username, password, config, id);
    }

    private static XMPPConnection newConnection(final String username, 
        final String password, final ConnectionConfiguration config,
        final String id) throws XMPPException {
        final XMPPConnection conn = new XMPPConnection(config);
        conn.connect();
        
        LOG.info("Connection is Secure: {}", conn.isSecureConnection());
        LOG.info("Connection is TLS: {}", conn.isUsingTLS());
        conn.login(username, password, id);
        
        while (!conn.isAuthenticated()) {
            LOG.info("Waiting for authentication");
            try {
                Thread.sleep(400);
            } catch (final InterruptedException e1) {
                LOG.error("Exception during sleep?", e1);
            }
        }
        
        conn.addConnectionListener(new ConnectionListener() {
            
            public void reconnectionSuccessful() {
                LOG.info("Reconnection successful...");
            }
            
            public void reconnectionFailed(final Exception e) {
                LOG.info("Reconnection failed", e);
            }
            
            public void reconnectingIn(final int time) {
                LOG.info("Reconnecting to XMPP server in "+time);
            }
            
            public void connectionClosedOnError(final Exception e) {
                LOG.info("XMPP connection closed on error", e);
                try {
                    persistentXmppConnection(username, password, id);
                } catch (final IOException e1) {
                    LOG.error("Could not re-establish connection?", e1);
                }
            }
            
            public void connectionClosed() {
                LOG.info("XMPP connection closed. Creating new connection.");
                try {
                    persistentXmppConnection(username, password, id);
                } catch (final IOException e1) {
                    LOG.error("Could not re-establish connection?", e1);
                }
            }
        });
        
        return conn;
    }

    public static boolean isDebug() {
        return true;
    }
    
    public static void writeCredentials(final String email, final String pwd) {
        PROPS.setProperty("google.user", email);
        PROPS.setProperty("google.pwd", pwd);
        persistProps();
    }

    public static String getStringProperty(final String key) {
        return PROPS.getProperty(key);
    }

    public static void clear(final String key) {
        PROPS.remove(key);
        persistProps();
    }

    private static void persistProps() {
        FileWriter fw = null;
        try {
            fw = new FileWriter(PROPS_FILE);
            PROPS.store(fw, "");
        } catch (final IOException e) {
            LOG.error("Could not store props?");
        } finally {
            IOUtils.closeQuietly(fw);
        }
    }
    
    /**
     * We subclass here purely to expose the encoding method of the built-in
     * request encoder.
     */
    private static final class RequestEncoder extends HttpRequestEncoder {
        private ChannelBuffer encode(final HttpRequest request, 
            final Channel ch) throws Exception {
            return (ChannelBuffer) super.encode(null, ch, request);
        }
    }

    public static byte[] toByteBuffer(final HttpRequest request,
        final ChannelHandlerContext ctx) throws Exception {
        // We need to convert the Netty message to raw bytes for sending over
        // the socket.
        final RequestEncoder encoder = new RequestEncoder();
        final ChannelBuffer cb = encoder.encode(request, ctx.getChannel());
        return toRawBytes(cb);
    }

    public static byte[] toRawBytes(final ChannelBuffer cb) {
        final ByteBuffer buf = cb.toByteBuffer();
        return ByteBufferUtils.toRawBytes(buf);
    }
}


