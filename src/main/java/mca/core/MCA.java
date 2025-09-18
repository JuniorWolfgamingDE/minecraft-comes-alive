package mca.core;

import com.google.gson.Gson;
import mca.api.API;
import mca.command.CommandAdminMCA;
import mca.command.CommandMCA;
import mca.core.forge.EventHooks;
import mca.core.forge.GuiHandler;
import mca.core.forge.NetMCA;
import mca.core.forge.ServerProxy;
import mca.core.minecraft.ItemsMCA;
import mca.core.minecraft.ProfessionsMCA;
import mca.core.minecraft.RoseGoldOreGenerator;
import mca.entity.EntityGrimReaper;
import mca.entity.EntityVillagerMCA;
import mca.enums.EnumGender;
import mca.util.Util;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.*;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.registry.EntityRegistry;
import net.minecraftforge.fml.common.registry.GameRegistry;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Mod(modid = MCA.MODID, name = MCA.NAME, version = MCA.VERSION, guiFactory = "mca.client.MCAGuiFactory")
public class MCA {
    public static final String MODID = "mca";
    public static final String NAME = "Minecraft Comes Alive";
    public static final String VERSION = "6.0.1";
    @SidedProxy(clientSide = "mca.core.forge.ClientProxy", serverSide = "mca.core.forge.ServerProxy")
    public static ServerProxy proxy;
    public static CreativeTabs creativeTab;
    @Mod.Instance
    private static MCA instance;
    private static Logger logger;
    private static Localizer localizer;
    private static Config config;
    private static long startupTimestamp;
    public static boolean updateAvailable = false;
    public String[] supporters = new String[0];
    private Set<String> uploadedReports = new HashSet<>();
    private ExecutorService uploadExecutor = Executors.newSingleThreadExecutor();

    public static Logger getLog() {
        return logger;
    }

    public static MCA getInstance() {
        return instance;
    }

    public static Localizer getLocalizer() {
        return localizer;
    }

    public static Config getConfig() {
        return config;
    }

    public static long getStartupTimestamp() {
        return startupTimestamp;
    }

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        startupTimestamp = new Date().getTime();
        instance = this;
        logger = event.getModLog();
        proxy.registerEntityRenderers();
        localizer = new Localizer();
        config = new Config(event);
        creativeTab = new CreativeTabs("MCA") {
            @Override
            public ItemStack getTabIconItem() {
                return new ItemStack(ItemsMCA.ENGAGEMENT_RING);
            }
        };
        MinecraftForge.EVENT_BUS.register(new EventHooks());
        NetworkRegistry.INSTANCE.registerGuiHandler(this, new GuiHandler());
        NetMCA.registerMessages();

        if (MCA.getConfig().allowUpdateChecking) {
            MCA.getLog().warn("Update checks have been removed by JuniorWMG. Please disable \"Allow Update Checking\" in the MCA config. This option is only kept for compatibility reasons.");
        }

        // I know, I've done this stupidly simple - but it works. Supporter names from the Internet Archive.
        supporters = "Furzball,wuffleoreo,Nicole,Nia,Alex,AdmiralWilson,Ty,onquicklylu,Perf3ctDude,Cezary,Kalika,UnidentifiedDuck,Theresa G.,Andrew C.,Joya F.,Mike E.".split(",");
        MCA.getLog().info("Loaded " + supporters.length + " last known supporters.");
    }

    @EventHandler
    public void init(FMLInitializationEvent event) {
        GameRegistry.registerWorldGenerator(new RoseGoldOreGenerator(), MCA.getConfig().roseGoldSpawnWeight);
        EntityRegistry.registerModEntity(new ResourceLocation(MODID, "EntityVillagerMCA"), EntityVillagerMCA.class, EntityVillagerMCA.class.getSimpleName(), 1120, this, 50, 2, true);
        EntityRegistry.registerModEntity(new ResourceLocation(MODID, "GrimReaperMCA"), EntityGrimReaper.class, EntityGrimReaper.class.getSimpleName(), 1121, this, 50, 2, true);
        ProfessionsMCA.registerCareers();

        proxy.registerModelMeshers();
        ItemsMCA.assignCreativeTabs();
    }

    @EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        API.init();
    }

    @EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandMCA());
        event.registerServerCommand(new CommandAdminMCA());
    }

    @EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        checkForCrashReports();
    }

    public String getRandomSupporter() {
        if (supporters.length > 0) {
            return supporters[new Random().nextInt(supporters.length)];
        } else {
            return API.getRandomName(EnumGender.getRandom());
        }
    }

    public void checkForCrashReports() {
        if (MCA.getConfig().allowCrashReporting) {
            File crashReportsFolder = new File(System.getProperty("user.dir") + "/crash-reports/");
            File[] crashReportFiles = crashReportsFolder.listFiles(File::isFile);
            try {
                if (crashReportFiles != null) {
                    Optional<File> newestFile = Arrays.stream(crashReportFiles).max(Comparator.comparingLong(File::lastModified));
                    if (newestFile.isPresent() && newestFile.get().lastModified() > startupTimestamp) {
                        String fileName = newestFile.get().getName();
                        if (!uploadedReports.contains(fileName)) {
                            uploadedReports.add(fileName);
                            uploadCrashReportAsync(newestFile.get());
                        }
                    }
                }
            } catch (Exception e) {
                MCA.getLog().error("An unexpected error occurred while checking for crash reports.", e);
            }
        }
    }

    private void uploadCrashReportAsync(File crashFile) {
        uploadExecutor.submit(() -> {
            try {
                MCA.getLog().warn("Crash detected! Attempting to upload report...");
                
                String content = "content=" + java.net.URLEncoder.encode(FileUtils.readFileToString(crashFile, "UTF-8"), "UTF-8");
                byte[] out = content.getBytes(StandardCharsets.UTF_8);
                
                HttpURLConnection http = (HttpURLConnection) new URL("https://api.mclo.gs/1/log").openConnection();
                http.setRequestMethod("POST");
                http.setDoOutput(true);
                http.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                
                http.getOutputStream().write(out);
                
                if (http.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(http.getInputStream()));
                    String response = reader.readLine();
                    reader.close();
                    
                    int urlStart = response.indexOf("\"url\":\"") + 7;
                    int urlEnd = response.indexOf("\"", urlStart);
                    String reportUrl = response.substring(urlStart, urlEnd).replace("\\/", "/");
                    
                    MCA.getLog().warn("Crash report uploaded successfully: " + reportUrl);
                } else {
                    MCA.getLog().error("Failed to submit crash report. Response code: " + http.getResponseCode());
                }
            } catch (IOException e) {
                MCA.getLog().error("An unexpected error occurred while uploading crash report.", e);
            }
        });
    }
}
