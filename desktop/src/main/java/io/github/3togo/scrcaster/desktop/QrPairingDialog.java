package io.github.3togo.scrcaster.desktop;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class QrPairingDialog extends JDialog {
    private QrPairing session;
    private SwingWorker<String, Void> worker;
    private BufferedImage qrImage;
    private final JLabel qr = new JLabel();
    private final JTextArea status = new JTextArea(3, 40);
    private final Consumer<String> paired;
    private final Supplier<QrPairing> sessions;

    QrPairingDialog(JFrame owner, Consumer<String> paired) {
        this(owner, paired, QrPairing::new);
    }
    QrPairingDialog(JFrame owner, Consumer<String> paired, Supplier<QrPairing> sessions) {
        super(owner, "Pair with QR code", true);
        this.paired = paired;
        this.sessions = sessions;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) { cancelSession(); }
        });
        JPanel content = new JPanel(new BorderLayout(8, 12));
        content.setBorder(new EmptyBorder(18, 18, 18, 18));
        JTextArea instructions = text("On Android 11 or newer, open Developer options → Wireless debugging → Pair device with QR code, then scan below.");
        instructions.setRows(3);
        content.add(instructions, BorderLayout.NORTH);
        qr.setHorizontalAlignment(SwingConstants.CENTER);
        qr.getAccessibleContext().setAccessibleName("Wireless debugging pairing QR code");
        content.add(qr, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(8, 8));
        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        status.setOpaque(false);
        bottom.add(status, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton regenerate = new JButton("New QR code");
        regenerate.addActionListener(e -> regenerate());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        actions.add(regenerate); actions.add(cancel);
        bottom.add(actions, BorderLayout.SOUTH);
        content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content);
        setSize(560, 580);
        setResizable(false);
        setLocationRelativeTo(owner);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        regenerate();
    }
    private static JTextArea text(String value) {
        JTextArea area = new JTextArea(value);
        area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true); area.setOpaque(false);
        return area;
    }
    private void cancelSession() {
        if (session != null) { session.close(); session = null; }
        if (worker != null) { worker.cancel(true); worker = null; }
        qr.setIcon(null);
        if (qrImage != null) { qrImage.flush(); qrImage = null; }
    }
    private void regenerate() {
        cancelSession();
        QrPairing current = sessions.get();
        session = current;
        try {
            qrImage = current.image();
            qr.setIcon(new ImageIcon(qrImage));
        } catch (Exception e) {
            current.close();
            status.setText("Unable to generate QR code: " + e.getMessage());
            return;
        }
        status.setText("Waiting for scan…");
        worker = new SwingWorker<>() {
            @Override protected String doInBackground() throws Exception {
                return current.awaitPairing(message -> SwingUtilities.invokeLater(() -> {
                    if (session == current) status.setText(message);
                }));
            }
            @Override protected void done() {
                if (session != current || isCancelled()) return;
                try {
                    String endpoint = get();
                    dispose();
                    paired.accept(endpoint);
                } catch (CancellationException ignored) {
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    status.setText(e.getCause().getMessage());
                    current.close();
                    qr.setIcon(null);
                    if (qrImage != null) { qrImage.flush(); qrImage = null; }
                }
            }
        };
        worker.execute();
    }
    @Override public void dispose() {
        cancelSession();
        super.dispose();
    }
}
