package io.github.togo3.scrcaster.desktop;

import javax.jmdns.*;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Direct multicast discovery; does not need Avahi or adb's mDNS daemon. */
final class MdnsDiscovery implements QrPairing.Discovery {
    private final List<JmDNS> clients = new ArrayList<>();
    private final Map<String, List<QrPairing.Service>> found = new ConcurrentHashMap<>();
    private boolean closed;

    @Override public void start() throws IOException {
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!network.isUp() || network.isLoopback() || !network.supportsMulticast()) continue;
            for (InetAddress address : Collections.list(network.getInetAddresses())) {
                // Android wireless debugging publishes IPv4; one listener per local IPv4.
                if (!(address instanceof Inet4Address) || address.isLinkLocalAddress()) continue;
                synchronized (this) { if (closed) return; }
                JmDNS client;
                try { client = JmDNS.create(address); }
                catch (IOException ignored) { continue; }
                synchronized (this) {
                    if (closed) { closeAsync(client); return; }
                    clients.add(client);
                }
                for (String type : List.of("_adb-tls-pairing._tcp.local.", "_adb-tls-connect._tcp.local.")) {
                    client.addServiceListener(type, new ServiceListener() {
                        private String key(ServiceEvent event) { return address.getHostAddress() + "/" + event.getType() + event.getName(); }
                        @Override public void serviceAdded(ServiceEvent event) {
                            client.requestServiceInfo(event.getType(), event.getName(), true, 1000);
                        }
                        @Override public void serviceRemoved(ServiceEvent event) { found.remove(key(event)); }
                        @Override public void serviceResolved(ServiceEvent event) {
                            List<QrPairing.Service> services = new ArrayList<>();
                            for (Inet4Address host : event.getInfo().getInet4Addresses()) {
                                services.add(new QrPairing.Service(event.getName(), type.replace(".local.", ""),
                                    host.getHostAddress() + ":" + event.getInfo().getPort()));
                            }
                            found.put(key(event), services);
                        }
                    });
                }
            }
        }
        synchronized (this) {
            if (!closed && clients.isEmpty()) throw new IOException("No multicast-capable network interface. Connect to the phone's Wi-Fi network.");
        }
    }
    @Override public List<QrPairing.Service> services() { return found.values().stream().flatMap(List::stream).distinct().toList(); }
    private static void closeAsync(JmDNS client) {
        Thread closer = new Thread(() -> { try { client.close(); } catch (IOException ignored) { } }, "mdns-close");
        closer.setDaemon(true);
        closer.start(); // Closing multicast sockets may block; never block Swing's event thread.
    }
    @Override public synchronized void close() {
        closed = true;
        clients.forEach(MdnsDiscovery::closeAsync);
        clients.clear();
        found.clear();
    }
}
