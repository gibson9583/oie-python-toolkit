/* SPDX-License-Identifier: MPL-2.0 */
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;
import javax.imageio.ImageIO;
import javax.swing.JLabel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import org.apache.commons.lang3.SerializationUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirth.connect.client.ui.LoadedExtensions;
import com.mirth.connect.client.ui.LoginPanel;
import com.mirth.connect.client.ui.Mirth;
import com.mirth.connect.client.ui.PlatformUI;
import com.mirth.connect.client.ui.panels.connectors.ConnectorPanel;
import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.converters.ObjectXMLSerializer;

/** Drives the shipped, visible Administrator; does not replace application classes. */
public class SwingConnectorRoundtrip {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<Map<String, Object>> observations = new ArrayList<>();
    private static Path evidence;

    // macOS ignores java.util.prefs.userRoot. Avoid changing the operator's UI preferences.
    public static class PrivatePreferencesFactory implements PreferencesFactory {
        private final Preferences root = new PrivatePreferences(null, "");
        public Preferences userRoot() { return root; }
        public Preferences systemRoot() { return root; }
    }
    private static class PrivatePreferences extends AbstractPreferences {
        private final Map<String, String> values = new LinkedHashMap<>();
        PrivatePreferences(AbstractPreferences parent, String name) { super(parent, name); }
        protected void putSpi(String key, String value) { values.put(key, value); }
        protected String getSpi(String key) { return values.get(key); }
        protected void removeSpi(String key) { values.remove(key); }
        protected void removeNodeSpi() { values.clear(); }
        protected String[] keysSpi() { return values.keySet().toArray(String[]::new); }
        protected String[] childrenNamesSpi() { return new String[0]; }
        protected AbstractPreferences childSpi(String name) { return new PrivatePreferences(this, name); }
        protected void syncSpi() {}
        protected void flushSpi() {}
    }

    private static <T> T edt(Callable<T> task) throws Exception {
        FutureTask<T> result = new FutureTask<>(task);
        SwingUtilities.invokeAndWait(result);
        return result.get();
    }
    private static Object field(Object target, String name) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field found = type.getDeclaredField(name);
                found.setAccessible(true);
                return found.get(target);
            } catch (NoSuchFieldException ignored) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void waitFor(Callable<Boolean> condition, String message) throws Exception {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.call()) return;
            Thread.sleep(150);
        }
        captureWindows("failure");
        throw new AssertionError(message);
    }
    private static void collectText(Component component, StringBuilder output) {
        if (component instanceof JLabel label) output.append(label.getText()).append('\n');
        if (component instanceof JTextComponent text) output.append(text.getText()).append('\n');
        if (component instanceof Container container) for (Component child : container.getComponents()) collectText(child, output);
    }
    private static void captureWindows(String prefix) throws Exception {
        edt(() -> {
            int index = 0;
            for (Window window : Window.getWindows()) if (window.isShowing()) {
                StringBuilder text = new StringBuilder(window.getClass().getName()).append('\n');
                collectText(window, text);
                String name = prefix + "-window-" + index++;
                Files.writeString(evidence.resolve(name + ".txt"), text.toString());
                var image = new BufferedImage(window.getWidth(), window.getHeight(), BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics(); window.printAll(graphics); graphics.dispose();
                ImageIO.write(image, "png", evidence.resolve(name + ".png").toFile());
            }
            return null;
        });
    }
    private static void record(String name, Object... pairs) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("check", name);
        for (int i = 0; i < pairs.length; i += 2) row.put((String) pairs[i], pairs[i + 1]);
        observations.add(row);
        JSON.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("observations.json").toFile(), observations);
        System.out.println(name);
    }
    private static boolean placeholder(Component component) {
        if (component instanceof JLabel && ((JLabel) component).getText().contains("Edit the Python script and environment in the Web Administrator.")) return true;
        if (component instanceof Container) for (Component child : ((Container) component).getComponents()) if (placeholder(child)) return true;
        return false;
    }
    private static void capture(String name) throws Exception {
        edt(() -> {
            var frame = PlatformUI.MIRTH_FRAME;
            require(frame.isShowing(), "Administrator must be visible");
            BufferedImage image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            frame.printAll(graphics);
            graphics.dispose();
            ImageIO.write(image, "png", evidence.resolve(name + ".png").toFile());
            return null;
        });
    }
    private static void checkPanel(ConnectorPanel panel, ConnectorProperties expected, String type) throws Exception {
        edt(() -> {
            var settings = panel.getConnectorSettingsPanel();
            require(settings.getClass().getSimpleName().equals(type), "Wrong installed placeholder: " + settings.getClass());
            require(settings.isShowing() && placeholder(settings), "Placeholder is not visibly mounted");
            String before = ObjectXMLSerializer.getInstance().serialize(expected);
            ConnectorProperties actual = panel.getProperties();
            String after = ObjectXMLSerializer.getInstance().serialize(actual);
            if (!actual.equals(expected)) {
                Files.writeString(evidence.resolve("loading-expected-" + type + ".xml"), before);
                Files.writeString(evidence.resolve("loading-actual-" + type + ".xml"), after);
                throw new AssertionError("Connector properties changed while loading " + type);
            }
            return null;
        });
        record("visible-placeholder-and-full-property-equality", "panel", type,
            "loadedFrom", panel.getConnectorSettingsPanel().getClass().getProtectionDomain().getCodeSource().getLocation().toString());
    }
    private static void open(Channel channel, String label) throws Exception {
        var frame = PlatformUI.MIRTH_FRAME;
        // Native ChannelPanel.retrieveChannels enriches its cache from the
        // separate metadata endpoint before doEditChannel clones the model.
        // A raw single-channel GET omits this metadata and is not that path.
        waitFor(() -> edt(() -> {
            var status = frame.channelPanel.getCachedChannelStatuses().get(channel.getId());
            return status != null && channel.getRevision().equals(status.getChannel().getRevision())
                && Integer.valueOf(1).equals(status.getChannel().getExportData().getMetadata().getUserId());
        }), "Native enriched channel cache did not settle");
        edt(() -> {
            Channel nativeChannel = frame.channelPanel.getCachedChannelStatuses().get(channel.getId()).getChannel();
            compare(channel, nativeChannel);
            frame.editChannel(SerializationUtils.clone(nativeChannel));
            return null;
        });
        var setup = frame.channelEditPanel;
        edt(() -> { ((JTabbedPane) field(setup, "channelView")).setSelectedIndex(1); return null; });
        checkPanel(setup.sourceConnectorPanel, channel.getSourceConnector().getProperties(), "PythonReceiverPanel");
        capture(label + "-reader");
        edt(() -> { ((JTabbedPane) field(setup, "channelView")).setSelectedIndex(2); return null; });
        JTable table = (JTable) field(setup, "destinationTable");
        // Cached Writer panels must follow selection in both directions without mixing scripts.
        for (int row : new int[]{0, 1, 0}) {
            edt(() -> { table.setRowSelectionInterval(row, row); return null; });
            checkPanel((ConnectorPanel) field(setup, "destinationConnectorPanel"), channel.getDestinationConnectors().get(row).getProperties(), "PythonDispatcherPanel");
            capture(label + "-writer-" + row);
        }
        record("open-existing-channel", "channelId", channel.getId(), "revision", channel.getRevision());
    }
    private static void compare(Channel expected, Channel actual) {
        require(expected.getSourceConnector().getProperties().equals(actual.getSourceConnector().getProperties()), "Reader properties changed after save");
        require(expected.getDestinationConnectors().size() == actual.getDestinationConnectors().size(), "Destination count changed");
        for (int i = 0; i < expected.getDestinationConnectors().size(); i++)
            require(expected.getDestinationConnectors().get(i).getProperties().equals(actual.getDestinationConnectors().get(i).getProperties()), "Writer " + i + " properties changed after save");
    }
    private static void execute(String[] args) throws Exception {
        evidence = Path.of(args[2]);
        Files.createDirectories(evidence);
        require(!GraphicsEnvironment.isHeadless(), "A real graphical session is required");
        Preferences preferences = Preferences.userNodeForPackage(Mirth.class);
        preferences.putInt("width", 1200);
        preferences.putInt("height", 850);
        preferences.putInt("maximizedState", java.awt.Frame.NORMAL);
        record("headful-shipped-administrator", "headless", false, "java", System.getProperty("java.runtime.version"),
            "loadedFrom", Mirth.class.getProtectionDomain().getCodeSource().getLocation().toString());
        Mirth.main(new String[]{args[0], args[1], "admin", System.getenv("OIE_SWING_TEST_PASSWORD")});
        waitFor(() -> edt(() -> PlatformUI.MIRTH_FRAME != null && PlatformUI.MIRTH_FRAME.channelEditPanel != null
            && PlatformUI.MIRTH_FRAME.isShowing() && !LoginPanel.getInstance().isVisible()), "Administrator login did not complete");
        var frame = PlatformUI.MIRTH_FRAME;
        require(LoadedExtensions.getInstance().getSourceConnectors().containsKey("Python Reader"), "Reader registration missing");
        require(LoadedExtensions.getInstance().getDestinationConnectors().containsKey("Python Writer"), "Writer registration missing");
        record("real-login-and-extension-discovery", "serverVersion", PlatformUI.SERVER_VERSION);
        edt(() -> { frame.setExtendedState(java.awt.Frame.NORMAL); frame.setSize(1200, 850); frame.doShowChannel(); return null; });
        waitFor(() -> edt(() -> frame.channelPanel.getCachedChannelStatuses().containsKey(args[4])
            && frame.channelPanel.getCachedChannelStatuses().containsKey(args[5])), "Channel list did not load");
        String mode = args[3];
        int iteration = 0;
        for (String id : new String[]{args[4], args[5], args[4]}) {
            Channel baseline = frame.mirthClient.getChannel(id, false);
            String label = mode + "-" + iteration++;
            open(baseline, label);
            if (mode.equals("save")) {
                String description = "Saved through real Swing Administrator: " + label;
                int revision = baseline.getRevision();
                edt(() -> {
                    ((JTextComponent) field(frame.channelEditPanel, "summaryDescriptionText")).setText(description);
                    frame.setSaveEnabled(true);
                    frame.doSaveChannel();
                    return null;
                });
                waitFor(() -> {
                    Channel saved = frame.mirthClient.getChannel(id, false);
                    return saved.getRevision() > revision && description.equals(saved.getDescription()) && edt(() -> !frame.isSaveEnabled());
                }, "Save action did not persist the change");
                Channel saved = frame.mirthClient.getChannel(id, false);
                compare(baseline, saved);
                record("native-save-action-persisted-full-properties", "channelId", id, "beforeRevision", revision, "afterRevision", saved.getRevision());
                edt(() -> { frame.doShowChannel(); return null; });
                open(frame.mirthClient.getChannel(id, false), label + "-reopened");
                compare(baseline, frame.mirthClient.getChannel(id, false));
                record("leave-and-reopen-preserves-full-properties", "channelId", id);
            } else record("fresh-process-reopen-preserves-full-properties", "channelId", id);
            edt(() -> { frame.setSaveEnabled(false); frame.doShowChannel(); return null; });
        }
        record("passed", "mode", mode);
        frame.mirthClient.logout();
        edt(() -> { for (Window window : Window.getWindows()) window.dispose(); return null; });
    }
    public static void main(String[] args) {
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(240_000); } catch (InterruptedException ignored) { return; }
            System.err.println("Swing qualification timed out");
            for (var entry : Thread.getAllStackTraces().entrySet()) {
                System.err.println(entry.getKey());
                for (var item : entry.getValue()) System.err.println("  " + item);
            }
            System.exit(2);
        });
        watchdog.setDaemon(true);
        watchdog.start();
        try { execute(args); System.exit(0); }
        catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
