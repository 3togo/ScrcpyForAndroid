package io.github.3togo.scrcaster.desktop;

import javax.swing.*;
import io.github.3togo.scrcaster.core.AspectRatio;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.prefs.Preferences;

/** Linux device manager. scrcpy owns the separate video/input window. */
public final class DesktopApp {
    private record CropPlan(String crop, boolean stretchCroppedFrame) { }

    private final Backend backend = new Backend();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Preferences prefs = Preferences.userNodeForPackage(DesktopApp.class);
    private final JFrame frame = new JFrame("ScrCaster");
    private final DefaultListModel<Backend.Device> devices = new DefaultListModel<>();
    private final JList<Backend.Device> deviceList = new JList<>(devices);
    private final JTextArea log = new JTextArea();
    private final JTextField address = new JTextField(24);
    private final JSpinner size = spinner("size", 1920, 0, 16384);
    private final JSpinner fps = spinner("fps", 60, 1, 240);
    private final JSpinner bitrate = spinner("bitrate", 8, 1, 200);
    private final JCheckBox audio = new JCheckBox("Audio", prefs.getBoolean("audio", true));
    private final JCheckBox control = new JCheckBox("Keyboard and mouse", prefs.getBoolean("control", true));
    private final JCheckBox fullscreen = new JCheckBox("Fullscreen", prefs.getBoolean("fullscreen", false));
    private final JCheckBox record = new JCheckBox("Record to file…");
    private final JComboBox<Backend.Fill> fill = new JComboBox<>(Backend.Fill.values());
    private final JComboBox<AspectRatio.Ratio> ratio = new JComboBox<>(AspectRatio.Ratio.values());
    private final JTextField customRatio = new JTextField(8);
    private final JButton start = new JButton("Start mirroring");
    private final JButton stop = new JButton("Stop mirroring");
    private final JLabel status = new JLabel("Ready");
    private volatile Process stream;
    private boolean starting;
    private boolean busy;
    private boolean closing;

    private JSpinner spinner(String key, int fallback, int min, int max) {
        int value = Math.max(min, Math.min(max, prefs.getInt(key, fallback)));
        return new JSpinner(new SpinnerNumberModel(value, min, max, 1));
    }
    private DesktopApp() {
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                closing = true;
                save();
                backend.close();
                worker.shutdownNow();
                frame.dispose();
            }
        });
        JPanel content = new JPanel(new BorderLayout(12, 12));
        content.setBorder(new EmptyBorder(18, 18, 18, 18));
        JPanel top = new JPanel(new GridLayout(0, 1, 4, 4));
        JLabel title = new JLabel("ScrCaster");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24));
        top.add(title);
        top.add(new JLabel("Connect an Android device over USB or wireless debugging."));
        address.setText(prefs.get("address", ""));
        address.setToolTipText("Wireless debugging connection address: host:port or [IPv6]:port");
        top.add(row(new JLabel("Address"), address, button("Connect", this::connect), button("Pair…", this::pair), button("Pair with QR…", this::pairQr)));
        top.add(new JLabel("Pairing uses the separate port shown under ‘Pair device with pairing code’."));
        content.add(top, BorderLayout.NORTH);

        deviceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        deviceList.addListSelectionListener(e -> updateActions());
        JPanel devicePanel = new JPanel(new BorderLayout(8, 8));
        devicePanel.setBorder(BorderFactory.createTitledBorder("Devices"));
        devicePanel.add(new JScrollPane(deviceList), BorderLayout.CENTER);
        devicePanel.add(row(button("Refresh", this::refresh), button("Discover", () -> task("Discovering wireless services", () -> {
            append(backend.adb("mdns", "services"));
            append("Use a discovered _adb-tls-connect address to connect, or _adb-tls-pairing address to pair.");
        })), button("Disconnect", this::disconnect)), BorderLayout.SOUTH);

        JPanel settings = new JPanel(new GridLayout(0, 2, 8, 8));
        settings.setBorder(BorderFactory.createTitledBorder("Stream settings"));
        settings.add(new JLabel("Maximum size (0 = original)")); settings.add(size);
        settings.add(new JLabel("Maximum FPS")); settings.add(fps);
        settings.add(new JLabel("Video bitrate (Mbps)")); settings.add(bitrate);
        settings.add(audio); settings.add(control);
        settings.add(fullscreen); settings.add(record);
        String savedFill = prefs.get("fill", Backend.Fill.CROP_LONG_EDGE.name());
        // Migrate the original single crop option to the finalized default semantics.
        if (savedFill.equals("CROP")) savedFill = Backend.Fill.CROP_LONG_EDGE.name();
        try { fill.setSelectedItem(Backend.Fill.valueOf(savedFill)); }
        catch (IllegalArgumentException ignored) { fill.setSelectedItem(Backend.Fill.CROP_LONG_EDGE); }
        fill.setEnabled(fullscreen.isSelected());
        fullscreen.addItemListener(e -> fill.setEnabled(fullscreen.isSelected()));
        settings.add(new JLabel("Fullscreen fill")); settings.add(fill);
        try { ratio.setSelectedItem(AspectRatio.Ratio.valueOf(prefs.get("ratio", AspectRatio.Ratio.DEVICE.name()))); }
        catch (IllegalArgumentException ignored) { ratio.setSelectedItem(AspectRatio.Ratio.DEVICE); }
        customRatio.setText(prefs.get("ratioCustom", ""));
        customRatio.setToolTipText("Aspect ratio as width:height, for example 21:9 or 1.78.");
        customRatio.setEnabled(ratio.getSelectedItem() == AspectRatio.Ratio.CUSTOM);
        ratio.addItemListener(e -> customRatio.setEnabled(ratio.getSelectedItem() == AspectRatio.Ratio.CUSTOM));
        JPanel ratioRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        ratioRow.add(ratio); ratioRow.add(customRatio);
        settings.add(new JLabel("Aspect ratio")); settings.add(ratioRow);
        start.addActionListener(e -> mirror());
        stop.addActionListener(e -> backend.stop(stream));
        settings.add(start); settings.add(stop);
        settings.add(button("Send file…", () -> transfer(true)));
        settings.add(button("Receive file…", () -> transfer(false)));
        JPanel center = new JPanel(new BorderLayout(12, 12));
        center.add(devicePanel, BorderLayout.CENTER);
        center.add(settings, BorderLayout.EAST);
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logs = new JScrollPane(log);
        logs.setBorder(BorderFactory.createTitledBorder("Activity"));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, center, logs);
        split.setResizeWeight(.6);
        content.add(split, BorderLayout.CENTER);
        content.add(status, BorderLayout.SOUTH);
        frame.setContentPane(content);
        frame.setMinimumSize(new Dimension(860, 560));
        frame.setSize(1040, 700);
        frame.setLocationRelativeTo(null);
        updateActions();
        frame.setVisible(true);
        task("Checking dependencies", () -> {
            append(backend.run(List.of(System.getenv().getOrDefault("SCRCPY", "scrcpy"), "--version"), null, java.time.Duration.ofSeconds(10)));
            append(backend.adb("version"));
            loadDevices();
        });
    }
    private static JPanel row(Component... items) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEADING, 8, 2));
        for (Component item : items) panel.add(item);
        return panel;
    }
    private JButton button(String title, Runnable action) {
        JButton button = new JButton(title);
        button.addActionListener(e -> {
            try { action.run(); } catch (Exception ex) { error(ex); }
        });
        return button;
    }
    private void updateActions() {
        Backend.Device device = deviceList.getSelectedValue();
        start.setEnabled(!busy && !starting && stream == null && device != null && device.ready());
        stop.setEnabled(stream != null);
    }
    private Backend.Device selected() {
        Backend.Device device = deviceList.getSelectedValue();
        if (device == null || !device.ready()) throw new IllegalArgumentException("Select an authorized, online device. Check the device for a USB debugging prompt.");
        return device;
    }
    private void append(String message) {
        SwingUtilities.invokeLater(() -> {
            log.append(message.stripTrailing() + "\n");
            if (log.getDocument().getLength() > 100_000) {
                try { log.getDocument().remove(0, log.getDocument().getLength() - 80_000); }
                catch (javax.swing.text.BadLocationException ignored) { }
            }
            log.setCaretPosition(log.getDocument().getLength());
        });
    }
    private void error(Exception ex) {
        if (closing) return;
        append("Error: " + ex.getMessage());
        JOptionPane.showMessageDialog(frame, ex.getMessage(), "Unable to complete action", JOptionPane.ERROR_MESSAGE);
    }
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private void task(String label, Work work) {
        task(label, work, () -> {});
    }
    private void task(String label, Work work, Runnable completed) {
        if (busy) { Toolkit.getDefaultToolkit().beep(); return; }
        busy = true;
        status.setText(label + "…");
        updateActions();
        worker.submit(() -> {
            boolean succeeded = false;
            try { work.run(); succeeded = true; }
            catch (Exception ex) { SwingUtilities.invokeLater(() -> error(ex)); }
            finally {
                boolean runCompletion = succeeded;
                SwingUtilities.invokeLater(() -> {
                    busy = false;
                    status.setText("Ready");
                    updateActions();
                    if (runCompletion && !closing) completed.run();
                });
            }
        });
    }
    private void loadDevices() throws Exception {
        loadDevices(null);
    }
    private void loadDevices(String preferredSerial) throws Exception {
        List<Backend.Device> found = Backend.parseDevices(backend.adb("devices", "-l"));
        SwingUtilities.invokeLater(() -> {
            Backend.Device previous = deviceList.getSelectedValue();
            devices.clear();
            found.forEach(devices::addElement);
            int selection = preferredSerial == null ? 0 : -1;
            String target = preferredSerial;
            if (target == null && previous != null) target = previous.serial();
            for (int i = 0; i < found.size(); i++)
                if (found.get(i).serial().equals(target)) selection = i;
            if (!found.isEmpty() && selection >= 0) deviceList.setSelectedIndex(selection);
            else if (!found.isEmpty()) deviceList.clearSelection();
            else append("No devices found. Connect with USB and authorize debugging, or enter a wireless address.");
        });
    }
    private void refresh() { task("Refreshing devices", this::loadDevices); }
    private void connect() {
        String endpoint = Backend.endpoint(address.getText());
        prefs.put("address", endpoint);
        task("Connecting", () -> { append(backend.adb("connect", endpoint)); loadDevices(); });
    }
    private void pair() {
        if (busy) return;
        JTextField endpoint = new JTextField(address.getText(), 24);
        JPasswordField code = new JPasswordField(6);
        JPanel form = new JPanel(new GridLayout(0, 1, 4, 4));
        form.add(new JLabel("Pairing address (uses a different port from Connect)")); form.add(endpoint);
        form.add(new JLabel("Six-digit pairing code")); form.add(code);
        if (JOptionPane.showConfirmDialog(frame, form, "Pair wireless device", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        String target = Backend.endpoint(endpoint.getText());
        char[] secret = code.getPassword();
        code.setText("");
        task("Pairing", () -> {
            append(backend.pair(target, secret));
            append("Now enter the connection address from the main Wireless debugging page and choose Connect.");
            loadDevices();
        });
    }
    private void pairQr() {
        if (busy) return;
        QrPairingDialog dialog = new QrPairingDialog(frame, connection -> {
            if (connection != null) {
                address.setText(connection);
                prefs.put("address", connection);
                append("QR pairing complete. Connected to " + connection + ".");
                task("Preparing mirroring", () -> loadDevices(connection), () -> mirror(connection));
            } else {
                append("QR pairing complete. If the device is not listed, enter the connection address from Wireless debugging and choose Connect.");
                refresh();
            }
        });
        dialog.setVisible(true);
    }
    private void disconnect() {
        String serial = selected().serial();
        if (!serial.contains(":" ) && !serial.endsWith("._adb-tls-connect._tcp"))
            throw new IllegalArgumentException("Unplug the cable to disconnect a USB device.");
        task("Disconnecting", () -> { append(backend.adb("disconnect", serial)); loadDevices(); });
    }
    private void mirror() {
        try { mirror(selected().serial()); }
        catch (Exception ex) { error(ex); }
    }
    private void mirror(String serial) {
        if (closing || busy || starting || stream != null) return;
        try {
            String output = "";
            if (record.isSelected()) {
                JFileChooser chooser = new JFileChooser();
                chooser.setSelectedFile(new java.io.File("scrcpy-" + System.currentTimeMillis() + ".mkv"));
                if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
                Path path = chooser.getSelectedFile().toPath().toAbsolutePath();
                if (!(path.toString().endsWith(".mkv") || path.toString().endsWith(".mp4")))
                    throw new IllegalArgumentException("Use a .mkv or .mp4 recording filename.");
                if (Files.exists(path) && JOptionPane.showConfirmDialog(frame, "Replace " + path + "?", "Existing recording", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
                output = path.toString();
            }
            size.commitEdit(); fps.commitEdit(); bitrate.commitEdit();
            int sizeValue = (int) size.getValue(), fpsValue = (int) fps.getValue(), bitrateValue = (int) bitrate.getValue();
            boolean audioOn = audio.isSelected(), controlOn = control.isSelected(), fullscreenOn = fullscreen.isSelected();
            Backend.Fill fillMode = (Backend.Fill) fill.getSelectedItem();
            AspectRatio.Ratio ratioMode = (AspectRatio.Ratio) ratio.getSelectedItem();
            String customRatioText = customRatio.getText().trim();
            if (ratioMode == AspectRatio.Ratio.CUSTOM) Backend.parseRatio(customRatioText); // validate before starting
            final String recording = output;
            save();
            starting = true;
            updateActions();
            task("Starting mirroring", () -> {
                try {
                    boolean cropToFill = ratioMode == AspectRatio.Ratio.DEVICE
                        && fillMode.cropsToFill() && fullscreenOn;
                    CropPlan fillPlan = cropToFill ? cropForScreen(serial, fillMode) : new CropPlan("", false);
                    String crop = ratioMode != AspectRatio.Ratio.DEVICE
                        ? cropForRatio(serial, ratioMode, customRatioText)
                        : fillPlan.crop();
                    Backend.Options options = new Backend.Options(sizeValue, fpsValue, bitrateValue,
                        audioOn, controlOn, fullscreenOn, fillMode, crop,
                        fillPlan.stretchCroppedFrame(), recording);
                    launch(serial, options, true);
                } finally { SwingUtilities.invokeLater(() -> { starting = false; updateActions(); }); }
            });
        } catch (Exception ex) { error(ex); }
    }
    /**
     * Starts scrcpy. Several device encoders cannot encode a cropped capture at all:
     * scrcpy logs "Capture/encoding error" and keeps retrying at ever smaller sizes,
     * ending up with a tiny picture. A rejected crop is therefore retried once without
     * cropping so mirroring still works.
     */
    private void launch(String serial, Backend.Options options, boolean retryWithoutCrop) {
        List<String> command = backend.streamCommand(serial, options);
        append("scrcpy " + String.join(" ", command.subList(1, command.size())));
        Process p;
        try { p = backend.start(command); }
        catch (IOException ex) { append("Mirroring: " + ex.getMessage()); return; }
        stream = p;
        SwingUtilities.invokeLater(this::updateActions);
        AtomicBoolean rejected = new AtomicBoolean();
        Thread reader = new Thread(() -> {
            try {
                Backend.drain(p, line -> {
                    append(line);
                    if (retryWithoutCrop && !options.crop().isBlank() && line.contains("Capture/encoding error")
                        && rejected.compareAndSet(false, true)) {
                        append("This device's video encoder rejected the cropped capture; retrying without cropping.");
                        backend.stop(p);
                    }
                });
                append("Mirroring ended (exit " + p.waitFor() + ").");
            } catch (Exception ex) { append("Mirroring: " + ex.getMessage()); }
            finally {
                backend.stop(p);
                stream = null;
                SwingUtilities.invokeLater(this::updateActions);
                if (rejected.get()) {
                    Backend.Options plain = new Backend.Options(options.size(), options.fps(), options.bitrate(),
                        options.audio(), options.control(), options.fullscreen(), options.fill(), "", false,
                        options.recording());
                    launch(serial, plain, false);
                }
            }
        }, "scrcpy-output");
        reader.setDaemon(true);
        reader.start();
    }
    /** Crop rect for a chosen aspect ratio; "" when unavailable or invalid. */
    private String cropForRatio(String serial, AspectRatio.Ratio mode, String custom) {
        try {
            // -s is required: adb refuses "shell" commands when more than one device is connected.
            int[] natural = Backend.naturalSize(backend.adb("-s", serial, "shell", "wm", "size"));
            boolean landscape = Backend.landscape(backend.adb("-s", serial, "shell", "dumpsys", "display"));
            double target = Backend.targetRatio(mode, landscape, custom);
            if (target <= 0) return "";
            return Backend.cropForRatio(natural[0], natural[1], landscape, target);
        } catch (Exception ex) {
            append("Aspect-ratio crop unavailable (" + ex.getMessage() + "); mirroring at the device ratio.");
            return "";
        }
    }
    /** Crop rect for fullscreen plus whether scrcpy may safely stretch it without distortion. */
    private CropPlan cropForScreen(String serial, Backend.Fill fillMode) {
        try {
            // -s is required: adb refuses "shell" commands when more than one device is connected.
            int[] natural = Backend.naturalSize(backend.adb("-s", serial, "shell", "wm", "size"));
            boolean landscape = Backend.landscape(backend.adb("-s", serial, "shell", "dumpsys", "display"));
            DisplayMode mode = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDisplayMode();
            boolean monitorLandscape = mode.getWidth() >= mode.getHeight();
            if (fillMode == Backend.Fill.CROP_SHORT_EDGE) {
                return new CropPlan(
                    Backend.coverCrop(natural[0], natural[1], landscape, mode.getWidth(), mode.getHeight()),
                    true
                );
            }
            return new CropPlan(
                Backend.fillCrop(natural[0], natural[1], landscape, mode.getWidth(), mode.getHeight()),
                landscape == monitorLandscape
            );
        } catch (Exception ex) {
            append("Crop to fill unavailable (" + ex.getMessage() + "); using fit instead.");
            return new CropPlan("", false);
        }
    }
    private void transfer(boolean push) {
        String serial = selected().serial();
        String remote = JOptionPane.showInputDialog(frame, push ? "Destination directory on Android" : "File path on Android", "/sdcard/Download/");
        if (remote == null) return;
        if (!remote.startsWith("/") || remote.indexOf('\0') >= 0) throw new IllegalArgumentException("Use an absolute Android path.");
        JFileChooser chooser = new JFileChooser();
        if (!push) chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        String local = chooser.getSelectedFile().getAbsolutePath();
        task(push ? "Sending file" : "Receiving file", () -> append(backend.adb(java.time.Duration.ofMinutes(10), "-s", serial, push ? "push" : "pull", push ? local : remote, push ? remote : local)));
    }
    private void save() {
        prefs.putInt("size", (int) size.getValue()); prefs.putInt("fps", (int) fps.getValue());
        prefs.putInt("bitrate", (int) bitrate.getValue()); prefs.putBoolean("audio", audio.isSelected());
        prefs.putBoolean("control", control.isSelected()); prefs.putBoolean("fullscreen", fullscreen.isSelected());
        prefs.put("fill", ((Backend.Fill) fill.getSelectedItem()).name());
        prefs.put("ratio", ((AspectRatio.Ratio) ratio.getSelectedItem()).name());
        prefs.put("ratioCustom", customRatio.getText().trim());
    }
    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("ScrCaster requires a graphical desktop (DISPLAY or a supported Wayland/XWayland session).");
            System.exit(1);
        }
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) { }
            new DesktopApp();
        });
    }
}
