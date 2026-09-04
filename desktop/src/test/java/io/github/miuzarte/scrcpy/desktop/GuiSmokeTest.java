package io.github.miuzarte.scrcpy.desktop;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Run on X11/XWayland or under xvfb-run; backend commands are replaced by true. */
public final class GuiSmokeTest {
    public static void main(String[] args) throws Exception {
        DesktopApp.main(args);
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = null;
            try {
                for (Frame candidate : Frame.getFrames())
                    if (candidate instanceof JFrame f && f.getTitle().equals("Scrcpy for Linux")) frame = f;
                if (frame == null || !frame.isShowing()) throw new AssertionError("Window did not open");
                assert find(frame, "Start mirroring") != null;
                assert !find(frame, "Start mirroring").isEnabled();
                assert !find(frame, "Stop mirroring").isEnabled();
                BufferedImage image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                frame.paint(graphics);
                graphics.dispose();
                Files.createDirectories(Path.of("build"));
                ImageIO.write(image, "png", Path.of("build/gui-smoke.png").toFile());
                assert find(frame, "Pair with QR…") != null;
                QrPairingDialog dialog = new QrPairingDialog(frame, endpoint -> { throw new AssertionError("Unexpected pairing"); },
                    () -> new QrPairing(new Backend(), java.time.Duration.ofSeconds(5), java.time.Duration.ZERO));
                Timer dismiss = new Timer(300, event -> {
                    try {
                        find(dialog, "New QR code").doClick();
                        BufferedImage qr = new BufferedImage(dialog.getWidth(), dialog.getHeight(), BufferedImage.TYPE_INT_RGB);
                        Graphics2D g = qr.createGraphics();
                        dialog.paint(g);
                        g.dispose();
                        ImageIO.write(qr, "png", Path.of("build/qr-smoke.png").toFile());
                    } catch (Exception e) { throw new RuntimeException(e); }
                    finally { dialog.dispose(); }
                });
                dismiss.setRepeats(false);
                dismiss.start();
                dialog.setVisible(true);
                assert !dialog.isDisplayable();
            } catch (Exception e) { throw new RuntimeException(e); }
            finally { if (frame != null) frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING)); }
        });
        System.out.println("Desktop window opened, rendered, and closed successfully.");
    }
    private static JButton find(Container root, String text) {
        for (Component component : root.getComponents()) {
            if (component instanceof JButton button && button.getText().equals(text)) return button;
            if (component instanceof Container container) {
                JButton result = find(container, text);
                if (result != null) return result;
            }
        }
        return null;
    }
}
