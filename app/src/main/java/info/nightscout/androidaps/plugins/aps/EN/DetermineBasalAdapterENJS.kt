package info.nightscout.androidaps.plugins.aps.EN

import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.R
import info.nightscout.androidaps.data.IobTotal
import info.nightscout.androidaps.data.MealData
import info.nightscout.androidaps.database.AppRepository
import info.nightscout.androidaps.database.ValueWrapper
import info.nightscout.androidaps.database.entities.Bolus
import info.nightscout.androidaps.database.entities.TherapyEvent
import info.nightscout.androidaps.extensions.convertedToAbsolute
import info.nightscout.androidaps.extensions.getPassedDurationToTimeInMinutes
import info.nightscout.androidaps.extensions.plannedRemainingMinutes
import info.nightscout.androidaps.interfaces.*
import info.nightscout.shared.logging.AAPSLogger
import info.nightscout.shared.logging.LTag
import info.nightscout.androidaps.plugins.aps.logger.LoggerCallback
import info.nightscout.androidaps.plugins.aps.loop.APSResult
import info.nightscout.androidaps.plugins.aps.loop.ScriptReader
import info.nightscout.androidaps.plugins.configBuilder.ConstraintChecker
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.GlucoseStatus
// import info.nightscout.androidaps.utils.DateUtil
import info.nightscout.androidaps.utils.MidnightTime
import info.nightscout.shared.SafeParse
import info.nightscout.androidaps.interfaces.ResourceHelper
import info.nightscout.shared.sharedPreferences.SP
import info.nightscout.androidaps.utils.stats.TddCalculator
import info.nightscout.androidaps.utils.stats.TirCalculator
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.mozilla.javascript.*
import org.mozilla.javascript.Function
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import kotlin.math.roundToInt

class DetermineBasalAdapterENJS internal constructor(private val scriptReader: ScriptReader, private val injector: HasAndroidInjector) : DetermineBasalAdapterInterface {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var constraintChecker: ConstraintChecker
    @Inject lateinit var sp: SP
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var repository: AppRepository
    // @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var tddCalculator: TddCalculator
    @Inject lateinit var tirCalculator: TirCalculator

    private var profile = JSONObject()
    private var mGlucoseStatus = JSONObject()
    private var iobData: JSONArray? = null
    private var mealData = JSONObject()
    private var currentTemp = JSONObject()
    private var autosensData = JSONObject()
    private var microBolusAllowed = false
    private var smbAlwaysAllowed = false
    private var currentTime: Long = 0
    private var saveCgmSource = false

    override var currentTempParam: String? = null
    override var iobDataParam: String? = null
    override var glucoseStatusParam: String? = null
    override var profileParam: String? = null
    override var mealDataParam: String? = null
    override var scriptDebug = ""

    @Suppress("SpellCheckingInspection")
    override operator fun invoke(): APSResult? {
        aapsLogger.debug(LTag.APS, ">>> Invoking determine_basal <<<")
        aapsLogger.debug(LTag.APS, "Glucose status: " + mGlucoseStatus.toString().also { glucoseStatusParam = it })
        aapsLogger.debug(LTag.APS, "IOB data:       " + iobData.toString().also { iobDataParam = it })
        aapsLogger.debug(LTag.APS, "Current temp:   " + currentTemp.toString().also { currentTempParam = it })
        aapsLogger.debug(LTag.APS, "Profile:        " + profile.toString().also { profileParam = it })
        aapsLogger.debug(LTag.APS, "Meal data:      " + mealData.toString().also { mealDataParam = it })
        aapsLogger.debug(LTag.APS, "Autosens data:  $autosensData")
        aapsLogger.debug(LTag.APS, "Reservoir data: " + "undefined")
        aapsLogger.debug(LTag.APS, "MicroBolusAllowed:  $microBolusAllowed")
        aapsLogger.debug(LTag.APS, "SMBAlwaysAllowed:  $smbAlwaysAllowed")
        aapsLogger.debug(LTag.APS, "CurrentTime: $currentTime")
        aapsLogger.debug(LTag.APS, "isSaveCgmSource: $saveCgmSource")
        var determineBasalResultEN: DetermineBasalResultEN? = null
        val rhino = Context.enter()
        val scope: Scriptable = rhino.initStandardObjects()
        // Turn off optimization to make Rhino Android compatible
        rhino.optimizationLevel = -1
        try {

            //register logger callback for console.log and console.error
            ScriptableObject.defineClass(scope, LoggerCallback::class.java)
            val myLogger = rhino.newObject(scope, "LoggerCallback", null)
            scope.put("console2", scope, myLogger)
            rhino.evaluateString(scope, readFile("OpenAPSAMA/loggerhelper.js"), "JavaScript", 0, null)

            //set module parent
            rhino.evaluateString(scope, "var module = {\"parent\":Boolean(1)};", "JavaScript", 0, null)
            rhino.evaluateString(scope, "var round_basal = function round_basal(basal, profile) { return basal; };", "JavaScript", 0, null)
            rhino.evaluateString(scope, "require = function() {return round_basal;};", "JavaScript", 0, null)

            //generate functions "determine_basal" and "setTempBasal"
            rhino.evaluateString(scope, readFile("EN/determine-basal.js"), "JavaScript", 0, null)
            rhino.evaluateString(scope, readFile("EN/basal-set-temp.js"), "setTempBasal.js", 0, null)
            val determineBasalObj = scope["determine_basal", scope]
            val setTempBasalFunctionsObj = scope["tempBasalFunctions", scope]

            //call determine-basal
            if (determineBasalObj is Function && setTempBasalFunctionsObj is NativeObject) {

                //prepare parameters
                val params = arrayOf(
                    makeParam(mGlucoseStatus, rhino, scope),
                    makeParam(currentTemp, rhino, scope),
                    makeParamArray(iobData, rhino, scope),
                    makeParam(profile, rhino, scope),
                    makeParam(autosensData, rhino, scope),
                    makeParam(mealData, rhino, scope),
                    setTempBasalFunctionsObj,
                    java.lang.Boolean.valueOf(microBolusAllowed),
                    makeParam(null, rhino, scope),  // reservoir data as undefined
                    java.lang.Long.valueOf(currentTime),
                    java.lang.Boolean.valueOf(saveCgmSource)
                )
                val jsResult = determineBasalObj.call(rhino, scope, scope, params) as NativeObject
                scriptDebug = LoggerCallback.scriptDebug

                // Parse the jsResult object to a JSON-String
                val result = NativeJSON.stringify(rhino, scope, jsResult, null, null).toString()
                aapsLogger.debug(LTag.APS, "Result: $result")
                try {
                    val resultJson = JSONObject(result)
                    determineBasalResultEN = DetermineBasalResultEN(injector, resultJson)
                } catch (e: JSONException) {
                    aapsLogger.error(LTag.APS, "Unhandled exception", e)
                }
            } else {
                aapsLogger.error(LTag.APS, "Problem loading JS Functions")
            }
        } catch (e: IOException) {
            aapsLogger.error(LTag.APS, "IOException")
        } catch (e: RhinoException) {
            aapsLogger.error(LTag.APS, "RhinoException: (" + e.lineNumber() + "," + e.columnNumber() + ") " + e.toString())
        } catch (e: IllegalAccessException) {
            aapsLogger.error(LTag.APS, e.toString())
        } catch (e: InstantiationException) {
            aapsLogger.error(LTag.APS, e.toString())
        } catch (e: InvocationTargetException) {
            aapsLogger.error(LTag.APS, e.toString())
        } finally {
            Context.exit()
        }
        glucoseStatusParam = mGlucoseStatus.toString()
        iobDataParam = iobData.toString()
        currentTempParam = currentTemp.toString()
        profileParam = profile.toString()
        mealDataParam = mealData.toString()
        return determineBasalResultEN
    }

    @Suppress("SpellCheckingInspection")
    override fun setData(
        profile: Profile,
        maxIob: Double,
        maxBasal: Double,
        minBg: Double,
        maxBg: Double,
        targetBg: Double,
        basalRate: Double,
        iobArray: Array<IobTotal>,
        glucoseStatus: GlucoseStatus,
        mealData: MealData,
        autosensDataRatio: Double,
        tempTargetSet: Boolean,
        microBolusAllowed: Boolean,
        uamAllowed: Boolean,
        advancedFiltering: Boolean,
        isSaveCgmSource: Boolean
    ) {
        val now = System.currentTimeMillis()
        val pump = activePlugin.activePump
        val pumpBolusStep = pump.pumpDescription.bolusStep
        this.profile.put("max_iob", maxIob)
        //mProfile.put("dia", profile.getDia());
        this.profile.put("type", "current")
        this.profile.put("max_daily_basal", profile.getMaxDailyBasal())
        this.profile.put("max_basal", maxBasal)
        this.profile.put("safety_maxbolus", sp.getDouble(R.string.key_treatmentssafety_maxbolus, 3.0))
        this.profile.put("min_bg", minBg.roundToInt())
        this.profile.put("max_bg", maxBg.roundToInt())
        this.profile.put("target_bg", targetBg.roundToInt())
        this.profile.put("carb_ratio", profile.getIc())
        this.profile.put("carb_ratio_midnight", profile.getIc(MidnightTime.calc(now)))
        this.profile.put("sens", profile.getIsfMgdl())
        this.profile.put("sens_midnight", profile.getIsfMgdl(MidnightTime.calc(now)))
        this.profile.put("max_daily_safety_multiplier", sp.getInt(R.string.key_openapsama_max_daily_safety_multiplier, 3))
        this.profile.put("current_basal_safety_multiplier", sp.getDouble(R.string.key_openapsama_current_basal_safety_multiplier, 4.0))

        this.profile.put("high_temptarget_raises_sensitivity", sp.getBoolean(R.string.key_high_temptarget_raises_sensitivity, ENDefaults.high_temptarget_raises_sensitivity))
        // this.profile.put("high_temptarget_raises_sensitivity", false)
        this.profile.put("low_temptarget_lowers_sensitivity", sp.getBoolean(R.string.key_low_temptarget_lowers_sensitivity, ENDefaults.low_temptarget_lowers_sensitivity))
        // this.profile.put("low_temptarget_lowers_sensitivity", false)
        this.profile.put("sensitivity_raises_target", sp.getBoolean(R.string.key_sensitivity_raises_target, ENDefaults.sensitivity_raises_target))
        this.profile.put("resistance_lowers_target", sp.getBoolean(R.string.key_resistance_lowers_target, ENDefaults.resistance_lowers_target))
        this.profile.put("adv_target_adjustments", ENDefaults.adv_target_adjustments)
        this.profile.put("exercise_mode", ENDefaults.exercise_mode)
        this.profile.put("half_basal_exercise_target", ENDefaults.half_basal_exercise_target)
        this.profile.put("maxCOB", ENDefaults.maxCOB)
        this.profile.put("skip_neutral_temps", pump.setNeutralTempAtFullHour())
        // min_5m_carbimpact is not used within SMB determinebasal
        //if (mealData.usedMinCarbsImpact > 0) {
        //    mProfile.put("min_5m_carbimpact", mealData.usedMinCarbsImpact);
        //} else {
        //    mProfile.put("min_5m_carbimpact", SP.getDouble(R.string.key_openapsama_min_5m_carbimpact, ENDefaults.min_5m_carbimpact));
        //}
        this.profile.put("remainingCarbsCap", ENDefaults.remainingCarbsCap)
        this.profile.put("enableUAM", uamAllowed)
        this.profile.put("A52_risk_enable", ENDefaults.A52_risk_enable)
        val smbEnabled = sp.getBoolean(R.string.key_use_smb, false)
        this.profile.put("SMBInterval", sp.getInt(R.string.key_smbinterval, ENDefaults.SMBInterval))
        this.profile.put("enableSMB_with_COB", smbEnabled && sp.getBoolean(R.string.key_enableSMB_with_COB, false))
        this.profile.put("enableSMB_with_temptarget", smbEnabled && sp.getBoolean(R.string.key_enableSMB_with_temptarget, false))
        this.profile.put("allowSMB_with_high_temptarget", smbEnabled && sp.getBoolean(R.string.key_allowSMB_with_high_temptarget, false))
        this.profile.put("enableSMB_always", smbEnabled && sp.getBoolean(R.string.key_enableSMB_always, false) && advancedFiltering)
        this.profile.put("enableSMB_after_carbs", smbEnabled && sp.getBoolean(R.string.key_enableSMB_after_carbs, false) && advancedFiltering)
        this.profile.put("maxSMBBasalMinutes", sp.getInt(R.string.key_smbmaxminutes, ENDefaults.maxSMBBasalMinutes))
        this.profile.put("maxUAMSMBBasalMinutes", sp.getInt(R.string.key_uamsmbmaxminutes, ENDefaults.maxUAMSMBBasalMinutes))
        //set the min SMB amount to be the amount set by the pump.
        this.profile.put("bolus_increment", pumpBolusStep)
        this.profile.put("carbsReqThreshold", sp.getInt(R.string.key_carbsReqThreshold, ENDefaults.carbsReqThreshold))
        this.profile.put("current_basal", basalRate)
        this.profile.put("temptargetSet", tempTargetSet)
        this.profile.put("use_autosens", sp.getBoolean(R.string.key_openapsama_useautosens, false))
        //this.profile.put("use_autosens", true)
        this.profile.put("autosens_min", SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_autosens_min, "0.8")))
        this.profile.put("autosens_max", SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_autosens_max, "1.2")))
//**********************************************************************************************************************************************
        // patches ==== START
        this.profile.put("EatingNowTimeStart", sp.getInt(R.string.key_eatingnow_timestart, 9))
        this.profile.put("EatingNowTimeEnd", sp.getInt(R.string.key_eatingnow_timeend, 17))
        this.profile.put("normal_target_bg", profile.getTargetMgdl().roundToInt())
        this.profile.put("enableGhostCOB", sp.getBoolean(R.string.key_use_ghostcob, false))
        this.profile.put("allowENWovernight", sp.getBoolean(R.string.key_use_enw_overnight, false))
        //this.profile.put("COBWindow", sp.getInt(R.string.key_eatingnow_cobboostminutes, 0))

        // Within the EN Window ********************************************************************************
        this.profile.put("ENWindow", sp.getInt(R.string.key_eatingnow_enwindowminutes, 0))
        this.profile.put("ENWIOBTrigger", sp.getDouble(R.string.key_enwindowiob, 0.0))
        val enwMinBolus = sp.getDouble(R.string.key_enwminbolus, 0.0)
        this.profile.put("ENWMinBolus", enwMinBolus)
        this.profile.put("ENautostart", sp.getBoolean(R.string.key_enautostart, false))

        // Breakfast / first meal
        this.profile.put("ENBkfstWindow", sp.getInt(R.string.key_enbkfstwindowminutes, 0))
        this.profile.put("BreakfastPct", sp.getInt(R.string.key_eatingnow_breakfastpct, 100))
        this.profile.put("Win_COB_maxBolus_breakfast", sp.getDouble(R.string.key_eatingnow_cobboost_maxbolus_breakfast, 0.0))
        this.profile.put("Win_UAM_maxBolus_breakfast", sp.getDouble(R.string.key_eatingnow_uam_maxbolus_breakfast, 0.0))
        // other meals
        this.profile.put("Win_COB_maxBolus", sp.getDouble(R.string.key_eatingnow_cobboost_maxbolus, 0.0))
        this.profile.put("Win_UAM_maxBolus", sp.getDouble(R.string.key_eatingnow_uamboost_maxbolus, 0.0))
        this.profile.put("UAMbgBoost", Profile.toMgdl(sp.getDouble(R.string.key_eatingnow_uambgboost, 0.0),profileFunction.getUnits()))

        // Outside of the EN Window ********************************************************************************
        this.profile.put("COB_maxBolus", sp.getDouble(R.string.key_eatingnow_isfboost_maxbolus, 0.0))
        this.profile.put("UAM_maxBolus", sp.getDouble(R.string.key_eatingnow_uam_maxbolus, 0.0))

        this.profile.put("SMBbgOffset", Profile.toMgdl(sp.getDouble(R.string.key_eatingnow_smbbgoffset, 0.0),profileFunction.getUnits()))
        this.profile.put("ISFbgscaler", sp.getDouble(R.string.key_eatingnow_isfbgscaler, 0.0))
        this.profile.put("MaxISFpct", sp.getInt(R.string.key_eatingnow_maxisfpct, 100))
        this.profile.put("useDynISF", sp.getBoolean(R.string.key_use_dynamicISF, true))

        this.profile.put("insulinType", activePlugin.activeInsulin.friendlyName)
        this.profile.put("insulinPeak", activePlugin.activeInsulin.insulinConfiguration.peak/60000)
        val lastCannula = repository.getLastTherapyRecordUpToNow(TherapyEvent.Type.CANNULA_CHANGE).blockingGet()
        val lastCannulaTime = if (lastCannula is ValueWrapper.Existing) lastCannula.value.timestamp else 0L
        this.profile.put("lastCannulaTime", lastCannulaTime)
        // val prevCannula = repository.getLastTherapyRecordUpToTime(TherapyEvent.Type.CANNULA_CHANGE,lastCannulaTime).blockingGet()
        // val prevCannulaTime = if (prevCannula is ValueWrapper.Existing) prevCannula.value.timestamp else 0L
        // this.profile.put("prevCannulaTime", prevCannulaTime)
        // patches ==== END
//**********************************************************************************************************************************************
        if (profileFunction.getUnits() == GlucoseUnit.MMOL) {
            this.profile.put("out_units", "mmol/L")
        }
        // val now = System.currentTimeMillis()
        val tb = iobCobCalculator.getTempBasalIncludingConvertedExtended(now)
        currentTemp.put("temp", "absolute")
        currentTemp.put("duration", tb?.plannedRemainingMinutes ?: 0)
        currentTemp.put("rate", tb?.convertedToAbsolute(now, profile) ?: 0.0)
        // as we have non default temps longer than 30 mintues
        if (tb != null) currentTemp.put("minutesrunning", tb.getPassedDurationToTimeInMinutes(now))

        iobData = iobCobCalculator.convertToJSONArray(iobArray)
        mGlucoseStatus.put("glucose", glucoseStatus.glucose)
        mGlucoseStatus.put("noise", glucoseStatus.noise)
        if (sp.getBoolean(R.string.key_always_use_shortavg, false)) {
            mGlucoseStatus.put("delta", glucoseStatus.shortAvgDelta)
        } else {
            mGlucoseStatus.put("delta", glucoseStatus.delta)
        }

        mGlucoseStatus.put("short_avgdelta", glucoseStatus.shortAvgDelta)
        mGlucoseStatus.put("long_avgdelta", glucoseStatus.longAvgDelta)
        mGlucoseStatus.put("date", glucoseStatus.date)
        this.mealData.put("carbs", mealData.carbs)
        this.mealData.put("mealCOB", mealData.mealCOB)
        this.mealData.put("slopeFromMaxDeviation", mealData.slopeFromMaxDeviation)
        this.mealData.put("slopeFromMinDeviation", mealData.slopeFromMinDeviation)
        this.mealData.put("lastBolusTime", mealData.lastBolusTime)
        this.mealData.put("lastCarbTime", mealData.lastCarbTime)

        // get the last carb time for EN activation
        val getlastCarbs = repository.getLastCarbsRecordWrapped().blockingGet()
        val lastCarbTime = if (getlastCarbs is ValueWrapper.Existing) getlastCarbs.value.timestamp else 0L
        this.mealData.put("lastNormalCarbTime", lastCarbTime)

        // get the first carb time since EN activation
        val ENStartTime = 3600000 * sp.getInt(R.string.key_eatingnow_timestart, 9) + MidnightTime.calc(now)
        val getCarbsSinceENStart = repository.getCarbsDataFromTime(ENStartTime,true).blockingGet()
        val firstCarbTime = getCarbsSinceENStart.lastOrNull()?.timestamp
        this.mealData.put("firstCarbTime",firstCarbTime)

        // get the FIRST bolus time since EN activation
        val firstENBolusTime = repository.getENBolusFromTimeOfType(ENStartTime,true, Bolus.Type.NORMAL, enwMinBolus ).blockingGet().lastOrNull()?.timestamp
        val firstENBolusUnits = repository.getENBolusFromTimeOfType(ENStartTime,true, Bolus.Type.NORMAL, enwMinBolus ).blockingGet().lastOrNull()?.amount
        this.mealData.put("firstENBolusTime",firstENBolusTime)
        this.mealData.put("firstENBolusUnits",firstENBolusUnits)

        // get the FIRST EN TT time since EN activation
        val firstENTempTargetTime = repository.getENTemporaryTargetDataFromTime(ENStartTime,true).blockingGet().lastOrNull()?.timestamp
        this.mealData.put("firstENTempTargetTime",firstENTempTargetTime)

        // get the current EN TT info
        val activeENTempTargetStartTime = repository.getENTemporaryTargetActiveAt(now).blockingGet().lastOrNull()?.timestamp
        this.mealData.put("activeENTempTargetStartTime",activeENTempTargetStartTime)
        val activeENTempTargetDuration = repository.getENTemporaryTargetActiveAt(now).blockingGet().lastOrNull()?.duration
        if (activeENTempTargetDuration != null) {
            this.mealData.put("activeENTempTargetDuration",activeENTempTargetDuration/60000)
        } else {
            this.mealData.put("activeENTempTargetDuration", 0)
        }

        // get the LAST bolus time since EN activation
        val lastENBolusTime = repository.getENBolusFromTimeOfType(ENStartTime,false, Bolus.Type.NORMAL, enwMinBolus ).blockingGet().lastOrNull()?.timestamp
        val lastENBolusUnits = repository.getENBolusFromTimeOfType(ENStartTime,false, Bolus.Type.NORMAL, enwMinBolus ).blockingGet().lastOrNull()?.amount
        this.mealData.put("lastENBolusTime",lastENBolusTime)
        this.mealData.put("lastENBolusUnits",lastENBolusUnits)

        // 3PM is used as a low basal point at which the rest of the day leverages for ISF variance when using one ISF in the profile
        this.profile.put("enableBasalAt3PM", sp.getBoolean(R.string.key_use_3pm_basal, false))
        this.profile.put("BasalAt3PM", profile.getBasal(3600000*15+MidnightTime.calc(now)))

        // TDD related functions
        val enableSensTDD = sp.getBoolean(R.string.key_use_sens_tdd, false)
        this.profile.put("use_sens_TDD", enableSensTDD) // Override profile ISF with TDD ISF if selected in prefs
        this.profile.put("sens_TDD_scale",SafeParse.stringToDouble(sp.getString(R.string.key_sens_tdd_scale,"100")))
        val enableSRTDD = sp.getBoolean(R.string.key_use_sr_tdd, false)
        this.profile.put("enableSRTDD", enableSRTDD)


        // storing TDD values in prefs, terrible but hopefully effective
        if (enableSensTDD || enableSRTDD) { // only do TDD if we have to

            // check when TDD last updated
            val TDDLastUpdate =  sp.getLong("TDDLastUpdate",0)
            val TDDHrSinceUpdate = (now - TDDLastUpdate) / 3600000

            if (TDDLastUpdate == 0L || TDDHrSinceUpdate > 12) {
                // Generate the data for the larger datasets every 12 hours

                var TDDAvg7d = tddCalculator.averageTDD(tddCalculator.calculate(7))?.totalAmount
                this.mealData.put("TDDAvg7d", TDDAvg7d)
                if (TDDAvg7d == 0.0 || TDDAvg7d == null ) TDDAvg7d = ((basalRate * 12)*100)/21
                sp.putDouble("TDDAvg7d", TDDAvg7d)

                // val TDDAvg1d = tddCalculator.averageTDD(tddCalculator.calculate(1))?.totalAmount
                // this.mealData.put("TDDAvg1d", TDDAvg1d)
                // TDDAvg1d?.let { sp.putDouble("TDDAvg1d", it) }

                // val TDDLast4h = tddCalculator.calculateDaily(-4, 0).totalAmount
                // this.mealData.put("TDDLast4h", TDDLast4h)
                // sp.putDouble("TDDLast4h", TDDLast4h)

                // val TDDLast8h = tddCalculator.calculateDaily(-8, 0).totalAmount
                // this.mealData.put("TDDLast8h", TDDLast8h)
                // sp.putDouble("TDDLast8h", TDDLast8h)

                // val TDDLast8hfor4h = TDDLast8h - TDDLast4h
                // this.mealData.put("TDDLast8hfor4h", TDDLast8hfor4h)
                // // this.mealData.put("TDDLast8hfor4h", tddCalculator.calculateDaily(-8, -4).totalAmount)
                // sp.putDouble("TDDLast8hfor4h", TDDLast8hfor4h)

                // val TDDLastCannula = tddCalculator.calculate(lastCannulaTime, now).totalAmount
                // this.mealData.put("TDDLastCannula", TDDLastCannula)
                // sp.putDouble("TDDLastCannula", TDDLastCannula)

                sp.putLong("TDDLastUpdate", now)
                this.mealData.put("TDDLastUpdate", sp.getLong("TDDLastUpdate", 0))

            } else {
                // use stored value where appropriate
                this.mealData.put("TDDAvg7d", sp.getDouble("TDDAvg7d", ((basalRate * 12)*100)/21))
            }

            // calculate the rest of the TDD data
            var TDDAvg1d = tddCalculator.averageTDD(tddCalculator.calculate(1))?.totalAmount
            if (TDDAvg1d == 0.0 || TDDAvg1d == null) TDDAvg1d = ((basalRate * 12)*100)/21
            this.mealData.put("TDDAvg1d", TDDAvg1d)

            val TDDLast4h = tddCalculator.calculateDaily(-4, 0).totalAmount
            this.mealData.put("TDDLast4h", TDDLast4h)

            val TDDLast8h = tddCalculator.calculateDaily(-8, 0).totalAmount
            this.mealData.put("TDDLast8h", TDDLast8h)

            val TDDLast8hfor4h = TDDLast8h - TDDLast4h
            this.mealData.put("TDDLast8hfor4h", TDDLast8hfor4h)
            // this.mealData.put("TDDLast8hfor4h", tddCalculator.calculateDaily(-8, -4).totalAmount)

            val TDDLastCannula = tddCalculator.calculate(lastCannulaTime, now).totalAmount
            this.mealData.put("TDDLastCannula", TDDLastCannula)

            this.mealData.put("TDDLastUpdate", sp.getLong("TDDLastUpdate", 0))
        }

        // TIR Windows - 4 hours prior to current time // 4.0 - 10.0
        val resistancePerHr = sp.getDouble(R.string.en_resistance_per_hour, 0.0)
        this.profile.put("resistancePerHr", sp.getDouble(R.string.en_resistance_per_hour, 0.0))
        if (resistancePerHr > 0) {
            this.mealData.put("TIRW4H", tirCalculator.averageTIR(tirCalculator.calculateHoursPrior(4, 3, 72.0, 160.0)).abovePct())
            this.mealData.put("TIRW3H", tirCalculator.averageTIR(tirCalculator.calculateHoursPrior(3, 2, 72.0, 160.0)).abovePct())
            this.mealData.put("TIRW2H", tirCalculator.averageTIR(tirCalculator.calculateHoursPrior(2, 1, 72.0, 160.0)).abovePct())
            this.mealData.put("TIRW1H", tirCalculator.averageTIR(tirCalculator.calculateHoursPrior(1, 0, 72.0, 160.0)).abovePct())
        }
        //
        // this.mealData.put("TIRW4",tirCalculator.averageTIR(tirCalculator.calculateHoursPrior(4,3,72.0, 180.0)).inRangePct())
        // this.mealData.put("TIRW3",tirCalculator.averageTIR(tirCalculator.calculateHoursPrior(3,2,72.0, 180.0)).inRangePct())
        // this.mealData.put("TIRW2",tirCalculator.averageTIR(tirCalculator.calculateHoursPrior(2,1,72.0, 180.0)).inRangePct())
        // this.mealData.put("TIRW1",tirCalculator.averageTIR(tirCalculator.calculateHoursPrior(1,0,72.0, 180.0)).inRangePct())
        //
        // this.mealData.put("TIRW4L",tirCalculator.averageTIR(tirCalculator.calculateHoursPrior(4,3,72.0, 180.0)).belowPct())
        // this.mealData.put("TIRW3L",tirCalculator.averageTIR(tirCalculator.calculateHoursPrior(3,2,72.0, 180.0)).belowPct())
        // this.mealData.put("TIRW2L",tirCalculator.averageTIR(tirCalculator.calculateHoursPrior(2,1,72.0, 180.0)).belowPct())
        // this.mealData.put("TIRW1L",tirCalculator.averageTIR(tirCalculator.calculateHoursPrior(1,0,72.0, 180.0)).belowPct())

        if (constraintChecker.isAutosensModeEnabled().value()) {
            autosensData.put("ratio", autosensDataRatio)
        } else {
            autosensData.put("ratio", 1.0)
        }
        this.microBolusAllowed = microBolusAllowed
        smbAlwaysAllowed = advancedFiltering
        currentTime = now
        saveCgmSource = isSaveCgmSource
    }

    private fun makeParam(jsonObject: JSONObject?, rhino: Context, scope: Scriptable): Any {
        return if (jsonObject == null) Undefined.instance
        else NativeJSON.parse(rhino, scope, jsonObject.toString()) { _: Context?, _: Scriptable?, _: Scriptable?, objects: Array<Any?> -> objects[1] }
    }

    private fun makeParamArray(jsonArray: JSONArray?, rhino: Context, scope: Scriptable): Any {
        return NativeJSON.parse(rhino, scope, jsonArray.toString()) { _: Context?, _: Scriptable?, _: Scriptable?, objects: Array<Any?> -> objects[1] }
    }

    @Throws(IOException::class) private fun readFile(filename: String): String {
        val bytes = scriptReader.readFile(filename)
        var string = String(bytes, StandardCharsets.UTF_8)
        if (string.startsWith("#!/usr/bin/env node")) {
            string = string.substring(20)
        }
        return string
    }

    init {
        injector.androidInjector().inject(this)
    }
}