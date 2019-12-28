package info.nightscout.androidaps.plugins.aps.openAPSAMA;

import org.json.JSONException;

import javax.inject.Inject;
import javax.inject.Singleton;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.IobTotal;
import info.nightscout.androidaps.data.MealData;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.db.TempTarget;
import info.nightscout.androidaps.interfaces.APSInterface;
import info.nightscout.androidaps.interfaces.PluginBase;
import info.nightscout.androidaps.interfaces.PluginDescription;
import info.nightscout.androidaps.interfaces.PluginType;
import info.nightscout.androidaps.interfaces.PumpInterface;
import info.nightscout.androidaps.logging.AAPSLogger;
import info.nightscout.androidaps.logging.LTag;
import info.nightscout.androidaps.plugins.aps.loop.APSResult;
import info.nightscout.androidaps.plugins.aps.loop.ScriptReader;
import info.nightscout.androidaps.plugins.aps.openAPSMA.events.EventOpenAPSUpdateGui;
import info.nightscout.androidaps.plugins.aps.openAPSMA.events.EventOpenAPSUpdateResultGui;
import info.nightscout.androidaps.plugins.bus.RxBus;
import info.nightscout.androidaps.plugins.configBuilder.ConfigBuilderPlugin;
import info.nightscout.androidaps.plugins.configBuilder.ConstraintChecker;
import info.nightscout.androidaps.plugins.configBuilder.ProfileFunctions;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.AutosensData;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.AutosensResult;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.GlucoseStatus;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.IobCobCalculatorPlugin;
import info.nightscout.androidaps.plugins.treatments.TreatmentsPlugin;
import info.nightscout.androidaps.utils.DateUtil;
import info.nightscout.androidaps.utils.FabricPrivacy;
import info.nightscout.androidaps.utils.HardLimits;
import info.nightscout.androidaps.utils.Profiler;
import info.nightscout.androidaps.utils.Round;

@Singleton
public class OpenAPSAMAPlugin extends PluginBase implements APSInterface {

    private final AAPSLogger aapsLogger;
    // last values
    DetermineBasalAdapterAMAJS lastDetermineBasalAdapterAMAJS = null;
    long lastAPSRun = 0;
    DetermineBasalResultAMA lastAPSResult = null;
    AutosensResult lastAutosensResult = null;

    @Inject
    public OpenAPSAMAPlugin(AAPSLogger aapsLogger) {
        super(new PluginDescription()
                .mainType(PluginType.APS)
                .fragmentClass(OpenAPSAMAFragment.class.getName())
                .pluginName(R.string.openapsama)
                .shortName(R.string.oaps_shortname)
                .preferencesId(R.xml.pref_openapsama)
                .description(R.string.description_ama)
        );
        this.aapsLogger = aapsLogger;
    }

    @Override
    public boolean specialEnableCondition() {
        PumpInterface pump = ConfigBuilderPlugin.getPlugin().getActivePump();
        return pump == null || pump.getPumpDescription().isTempBasalCapable;
    }

    @Override
    public boolean specialShowInListCondition() {
        PumpInterface pump = ConfigBuilderPlugin.getPlugin().getActivePump();
        return pump == null || pump.getPumpDescription().isTempBasalCapable;
    }

    @Override
    public APSResult getLastAPSResult() {
        return lastAPSResult;
    }

    @Override
    public long getLastAPSRun() {
        return lastAPSRun;
    }

    @Override
    public void invoke(String initiator, boolean tempBasalFallback) {
        aapsLogger.debug(LTag.APS, "invoke from " + initiator + " tempBasalFallback: " + tempBasalFallback);
        lastAPSResult = null;
        DetermineBasalAdapterAMAJS determineBasalAdapterAMAJS;
        determineBasalAdapterAMAJS = new DetermineBasalAdapterAMAJS(new ScriptReader(MainApp.instance().getBaseContext()), aapsLogger);

        GlucoseStatus glucoseStatus = GlucoseStatus.getGlucoseStatusData();
        Profile profile = ProfileFunctions.getInstance().getProfile();
        PumpInterface pump = ConfigBuilderPlugin.getPlugin().getActivePump();

        if (profile == null) {
            RxBus.INSTANCE.send(new EventOpenAPSUpdateResultGui(MainApp.gs(R.string.noprofileselected)));
            aapsLogger.debug(LTag.APS, MainApp.gs(R.string.noprofileselected));
            return;
        }

        if (pump == null) {
            RxBus.INSTANCE.send(new EventOpenAPSUpdateResultGui(MainApp.gs(R.string.nopumpselected)));
            aapsLogger.debug(LTag.APS, MainApp.gs(R.string.nopumpselected));
            return;
        }

        if (!isEnabled(PluginType.APS)) {
            RxBus.INSTANCE.send(new EventOpenAPSUpdateResultGui(MainApp.gs(R.string.openapsma_disabled)));
            aapsLogger.debug(LTag.APS, MainApp.gs(R.string.openapsma_disabled));
            return;
        }

        if (glucoseStatus == null) {
            RxBus.INSTANCE.send(new EventOpenAPSUpdateResultGui(MainApp.gs(R.string.openapsma_noglucosedata)));
            aapsLogger.debug(LTag.APS, MainApp.gs(R.string.openapsma_noglucosedata));
            return;
        }

        double maxBasal = ConstraintChecker.getInstance().getMaxBasalAllowed(profile).value();
        double minBg = profile.getTargetLowMgdl();
        double maxBg = profile.getTargetHighMgdl();
        double targetBg = profile.getTargetMgdl();

        minBg = Round.roundTo(minBg, 0.1d);
        maxBg = Round.roundTo(maxBg, 0.1d);

        long start = System.currentTimeMillis();
        long startPart = System.currentTimeMillis();
        IobTotal[] iobArray = IobCobCalculatorPlugin.getPlugin().calculateIobArrayInDia(profile);
        Profiler.log(aapsLogger, LTag.APS, "calculateIobArrayInDia()", startPart);

        startPart = System.currentTimeMillis();
        MealData mealData = TreatmentsPlugin.getPlugin().getMealData();
        Profiler.log(aapsLogger, LTag.APS, "getMealData()", startPart);

        double maxIob = ConstraintChecker.getInstance().getMaxIOBAllowed().value();

        minBg = HardLimits.verifyHardLimits(minBg, "minBg", HardLimits.VERY_HARD_LIMIT_MIN_BG[0], HardLimits.VERY_HARD_LIMIT_MIN_BG[1]);
        maxBg = HardLimits.verifyHardLimits(maxBg, "maxBg", HardLimits.VERY_HARD_LIMIT_MAX_BG[0], HardLimits.VERY_HARD_LIMIT_MAX_BG[1]);
        targetBg = HardLimits.verifyHardLimits(targetBg, "targetBg", HardLimits.VERY_HARD_LIMIT_TARGET_BG[0], HardLimits.VERY_HARD_LIMIT_TARGET_BG[1]);

        boolean isTempTarget = false;
        TempTarget tempTarget = TreatmentsPlugin.getPlugin().getTempTargetFromHistory(System.currentTimeMillis());
        if (tempTarget != null) {
            isTempTarget = true;
            minBg = HardLimits.verifyHardLimits(tempTarget.low, "minBg", HardLimits.VERY_HARD_LIMIT_TEMP_MIN_BG[0], HardLimits.VERY_HARD_LIMIT_TEMP_MIN_BG[1]);
            maxBg = HardLimits.verifyHardLimits(tempTarget.high, "maxBg", HardLimits.VERY_HARD_LIMIT_TEMP_MAX_BG[0], HardLimits.VERY_HARD_LIMIT_TEMP_MAX_BG[1]);
            targetBg = HardLimits.verifyHardLimits(tempTarget.target(), "targetBg", HardLimits.VERY_HARD_LIMIT_TEMP_TARGET_BG[0], HardLimits.VERY_HARD_LIMIT_TEMP_TARGET_BG[1]);
        }


        if (!HardLimits.checkOnlyHardLimits(profile.getDia(), "dia", HardLimits.MINDIA, HardLimits.MAXDIA))
            return;
        if (!HardLimits.checkOnlyHardLimits(profile.getIcTimeFromMidnight(Profile.secondsFromMidnight()), "carbratio", HardLimits.MINIC, HardLimits.MAXIC))
            return;
        if (!HardLimits.checkOnlyHardLimits(profile.getIsfMgdl(), "sens", HardLimits.MINISF, HardLimits.MAXISF))
            return;
        if (!HardLimits.checkOnlyHardLimits(profile.getMaxDailyBasal(), "max_daily_basal", 0.02, HardLimits.maxBasal()))
            return;
        if (!HardLimits.checkOnlyHardLimits(pump.getBaseBasalRate(), "current_basal", 0.01, HardLimits.maxBasal()))
            return;

        startPart = System.currentTimeMillis();
        if (ConstraintChecker.getInstance().isAutosensModeEnabled().value()) {
            AutosensData autosensData = IobCobCalculatorPlugin.getPlugin().getLastAutosensDataSynchronized("OpenAPSPlugin");
            if (autosensData == null) {
                RxBus.INSTANCE.send(new EventOpenAPSUpdateResultGui(MainApp.gs(R.string.openaps_noasdata)));
                return;
            }
            lastAutosensResult = autosensData.autosensResult;
        } else {
            lastAutosensResult = new AutosensResult();
            lastAutosensResult.sensResult = "autosens disabled";
        }
        Profiler.log(aapsLogger, LTag.APS, "detectSensitivityandCarbAbsorption()", startPart);
        Profiler.log(aapsLogger, LTag.APS, "AMA data gathering", start);

        start = System.currentTimeMillis();

        try {
            determineBasalAdapterAMAJS.setData(profile, maxIob, maxBasal, minBg, maxBg, targetBg, ConfigBuilderPlugin.getPlugin().getActivePump().getBaseBasalRate(), iobArray, glucoseStatus, mealData,
                    lastAutosensResult.ratio, //autosensDataRatio
                    isTempTarget
            );
        } catch (JSONException e) {
            FabricPrivacy.logException(e);
            return;
        }


        DetermineBasalResultAMA determineBasalResultAMA = determineBasalAdapterAMAJS.invoke();
        Profiler.log(aapsLogger, LTag.APS, "AMA calculation", start);
        // Fix bug determine basal
        if (determineBasalResultAMA == null) {
            aapsLogger.error(LTag.APS, "SMB calculation returned null");
            lastDetermineBasalAdapterAMAJS = null;
            lastAPSResult = null;
            lastAPSRun = 0;
        } else {
            if (determineBasalResultAMA.rate == 0d && determineBasalResultAMA.duration == 0 && !TreatmentsPlugin.getPlugin().isTempBasalInProgress())
                determineBasalResultAMA.tempBasalRequested = false;

            determineBasalResultAMA.iob = iobArray[0];

            long now = System.currentTimeMillis();

            try {
                determineBasalResultAMA.json.put("timestamp", DateUtil.toISOString(now));
            } catch (JSONException e) {
                aapsLogger.error(LTag.APS, "Unhandled exception", e);
            }

            lastDetermineBasalAdapterAMAJS = determineBasalAdapterAMAJS;
            lastAPSResult = determineBasalResultAMA;
            lastAPSRun = now;
        }
        RxBus.INSTANCE.send(new EventOpenAPSUpdateGui());

        //deviceStatus.suggested = determineBasalResultAMA.json;
    }

}
