import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.os.Looper;
import android.view.Display;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.json.JSONArray;
import org.json.JSONObject;

/** Shell/Shizuku user-service prototype. No production settings or framework files are modified. */
public final class HeadlessDex {
    private static final String WIFI_PERMISSION = "android.permission.CONFIGURE_WIFI_DISPLAY";
    private static final String DESKTOP_FEATURE = "com.samsung.android.desktopmode.DesktopModeFeature";

    private static Object service(String name, String stub) throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, name);
        if (binder == null) throw new IllegalStateException("Missing service: " + name);
        return Class.forName(stub).getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    private static Throwable cause(Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
        return error;
    }

    private static boolean feature(String key) throws Exception {
        return Class.forName(DESKTOP_FEATURE).getField(key).getBoolean(null);
    }

    private static JSONObject state(Object desktop, Class<?> api) throws Exception {
        Object state = api.getMethod("getDesktopModeState").invoke(desktop);
        return new JSONObject().put("enabled", state.getClass().getMethod("getEnabled").invoke(state))
                .put("display_type", state.getClass().getMethod("getDisplayType").invoke(state));
    }

    private static JSONArray displays(DisplayManager manager) throws Exception {
        JSONArray result = new JSONArray();
        for (Display display : manager.getDisplays()) {
            int type = (int) Display.class.getMethod("getType").invoke(display);
            result.put(new JSONObject().put("id", display.getDisplayId()).put("type", type)
                    .put("flags", display.getFlags()).put("state", display.getState()));
        }
        return result;
    }

    public static void main(String[] args) {
        JSONObject report = new JSONObject();
        int exitCode = 1;
        Object displayService = null;
        Class<?> displayApi = null;
        boolean requestAccepted = false;
        LocalWfdSink sink = null;
        String stage = "initialize";
        try {
            boolean attempt = args.length == 1 && "--attempt".equals(args[0]);
            if (args.length > 0 && !attempt && !(args.length == 1 && "--diagnose".equals(args[0])))
                throw new IllegalArgumentException("Usage: HeadlessDex [--diagnose|--attempt]");
            Looper.prepareMainLooper();
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            Context context = (Context) at.getMethod("getSystemContext").invoke(thread);
            Context shell = context.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
            DisplayManager manager = (DisplayManager) shell.getSystemService(Context.DISPLAY_SERVICE);
            Object desktop = service("desktopmode", "com.samsung.android.desktopmode.IDesktopMode$Stub");
            Class<?> desktopApi = Class.forName("com.samsung.android.desktopmode.IDesktopMode");
            report.put("uid", android.os.Process.myUid()).put("wifi_permission", shell.checkSelfPermission(WIFI_PERMISSION))
                    .put("standalone_supported", feature("SUPPORT_STANDALONE"))
                    .put("desktop_debug", feature("DEBUG")).put("before", state(desktop, desktopApi))
                    .put("displays_before", displays(manager));
            if (!attempt) {
                report.put("result", "diagnostics_only");
                exitCode = 0;
            } else {
                stage = "guard";
                if (report.getJSONObject("before").getInt("enabled") != 2)
                    throw new IllegalStateException("Existing/transitioning DeX session; left unchanged");
                for (int i = 0; i < report.getJSONArray("displays_before").length(); i++) {
                    JSONObject d = report.getJSONArray("displays_before").getJSONObject(i);
                    int type = d.getInt("type"), flags = d.getInt("flags");
                    if (d.getInt("id") != 0 && (type == 2 || type == 1000 || type == 1001 ||
                            (flags & (134217728 | 67108864)) != 0))
                        throw new IllegalStateException("Existing external display; left unchanged");
                }
                Object wifi = DisplayManager.class.getMethod("getWifiDisplayStatus").invoke(manager);
                int active = (int) wifi.getClass().getMethod("getActiveDisplayState").invoke(wifi);
                report.put("wifi_active_before", active);
                if (active != 0) throw new IllegalStateException("Existing Wi-Fi display session; left unchanged");
                stage = "build_loopback_config";
                Class<?> builderType = Class.forName("android.hardware.display.SemWifiDisplayConfig$Builder");
                Object builder = builderType.getConstructor().newInstance();
                builderType.getMethod("setApConnection", String.class, String.class, String.class, String.class)
                        .invoke(builder, "127.0.0.1", "7236", "Sunshine Local DeX", "02:00:00:00:00:01");
                builderType.getMethod("setMode", int.class).invoke(builder, 2); // MODE_WIRELESS_DEX
                Object config = builderType.getMethod("build").invoke(builder);
                report.put("peer", "127.0.0.1:7236").put("mode", "wireless_dex");
                displayService = service("display", "android.hardware.display.IDisplayManager$Stub");
                displayApi = Class.forName("android.hardware.display.IDisplayManager");
                Method connect = displayApi.getMethod("connectWifiDisplayWithConfig", config.getClass(),
                        Class.forName("android.hardware.display.IWifiDisplayConnectionCallback"));
                stage = "connect_wifi_display_binder";
                connect.invoke(displayService, config, null);
                requestAccepted = true;
                report.put("binder_request_accepted", true);
                stage = "local_wfd_handshake";
                sink = new LocalWfdSink();
                sink.start();
                for (int i = 0; i < 100; i++) {
                    Thread.sleep(200);
                    if (state(desktop, desktopApi).getInt("enabled") == 4) break;
                    if (sink.failure != null) throw sink.failure;
                }
                JSONObject after = state(desktop, desktopApi);
                report.put("after", after).put("displays_after", displays(manager))
                        .put("rtsp", new JSONObject(sink.snapshot()));
                if (after.getInt("enabled") != 4 || after.getInt("display_type") != 102)
                    throw new IllegalStateException("Native dual DeX did not activate");
                report.put("result", "native_dex_activated_capture_not_yet_verified");
                exitCode = 0;
            }
        } catch (Throwable error) {
            Throwable root = cause(error);
            try {
                report.put("result", root instanceof SecurityException ? "blocked_by_system_permission" : "failed")
                        .put("stage", stage).put("error_type", root.getClass().getName()).put("error", String.valueOf(root.getMessage()));
            } catch (Exception ignored) {}
            exitCode = 3;
        } finally {
            if (sink != null) {
                try { report.put("rtsp", new JSONObject(sink.snapshot())); } catch (Exception ignored) {}
                try { sink.close(); } catch (Exception ignored) {}
            }
            if (requestAccepted) {
                try {
                    displayApi.getMethod("disconnectWifiDisplay").invoke(displayService);
                    report.put("owned_session_disconnected", true);
                } catch (Throwable error) {
                    try { report.put("cleanup_error", cause(error).toString()); } catch (Exception ignored) {}
                    exitCode = 4;
                }
            }
            System.out.println(report.toString());
        }
        System.exit(exitCode);
    }
}
