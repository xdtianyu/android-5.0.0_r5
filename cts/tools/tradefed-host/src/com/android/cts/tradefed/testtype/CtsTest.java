/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.cts.tradefed.testtype;

import com.android.cts.tradefed.build.CtsBuildHelper;
import com.android.cts.tradefed.device.DeviceInfoCollector;
import com.android.cts.tradefed.result.CtsTestStatus;
import com.android.cts.tradefed.result.PlanCreator;
import com.android.cts.util.AbiUtils;
import com.android.ddmlib.Log;
import com.android.ddmlib.Log.LogLevel;
import com.android.ddmlib.testrunner.TestIdentifier;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.Option.Importance;
import com.android.tradefed.config.OptionCopier;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.TestDeviceOptions;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.ResultForwarder;
import com.android.tradefed.testtype.IAbi;
import com.android.tradefed.testtype.IBuildReceiver;
import com.android.tradefed.testtype.IDeviceTest;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.IResumableTest;
import com.android.tradefed.testtype.IShardableTest;
import com.android.tradefed.util.AbiFormatter;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.xml.AbstractXmlParser.ParseException;

import junit.framework.Test;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;


/**
 * A {@link Test} for running CTS tests.
 * <p/>
 * Supports running all the tests contained in a CTS plan, or individual test packages.
 */
public class CtsTest implements IDeviceTest, IResumableTest, IShardableTest, IBuildReceiver {
    private static final String LOG_TAG = "CtsTest";

    public static final String PLAN_OPTION = "plan";
    private static final String PACKAGE_OPTION = "package";
    private static final String CLASS_OPTION = "class";
    private static final String METHOD_OPTION = "method";
    private static final String TEST_OPTION = "test";
    public static final String CONTINUE_OPTION = "continue-session";
    public static final String RUN_KNOWN_FAILURES_OPTION = "run-known-failures";

    public static final String PACKAGE_NAME_METRIC = "packageName";
    public static final String PACKAGE_ABI_METRIC = "packageAbi";
    public static final String PACKAGE_DIGEST_METRIC = "packageDigest";

    private ITestDevice mDevice;

    @Option(name = PLAN_OPTION, description = "the test plan to run.",
            importance = Importance.IF_UNSET)
    private String mPlanName = null;

    @Option(name = PACKAGE_OPTION, shortName = 'p', description = "the test packages(s) to run.",
            importance = Importance.IF_UNSET)
    private Collection<String> mPackageNames = new ArrayList<String>();

    @Option(name = "exclude-package", description = "the test packages(s) to exclude from the run.")
    private Collection<String> mExcludedPackageNames = new ArrayList<String>();

    @Option(name = CLASS_OPTION, shortName = 'c', description = "run a specific test class.",
            importance = Importance.IF_UNSET)
    private String mClassName = null;

    @Option(name = METHOD_OPTION, shortName = 'm',
            description = "run a specific test method, from given --class.",
            importance = Importance.IF_UNSET)
    private String mMethodName = null;

    @Option(name = TEST_OPTION, shortName = 't', description = "run a specific test",
            importance = Importance.IF_UNSET)
    private String mTestName = null;

    @Option(name = CONTINUE_OPTION,
            description = "continue a previous test session.",
            importance = Importance.IF_UNSET)
    private Integer mContinueSessionId = null;

    @Option(name = "skip-device-info", shortName = 'd', description =
        "flag to control whether to collect info from device. Providing this flag will speed up " +
        "test execution for short test runs but will result in required data being omitted from " +
        "the test report.")
    private boolean mSkipDeviceInfo = false;

    @Option(name = "resume", description =
        "flag to attempt to automatically resume aborted test run on another connected device. ")
    private boolean mResume = false;

    @Option(name = "shards", description =
        "shard the tests to run into separately runnable chunks to execute on multiple devices " +
        "concurrently.")
    private int mShards = 1;

    @Option(name = "screenshot", description =
        "flag for taking a screenshot of the device when test execution is complete.")
    private boolean mScreenshot = false;

    @Option(name = "bugreport", shortName = 'b', description =
        "take a bugreport after each failed test. " +
        "Warning: can potentially use a lot of disk space.")
    private boolean mBugreport = false;

    @Option(name = RUN_KNOWN_FAILURES_OPTION, shortName = 'k', description =
        "run tests including known failures")
    private boolean mIncludeKnownFailures;

    @Option(name = "disable-reboot", description =
            "Do not reboot device after running some amount of tests. Default behavior is to reboot.")
    private boolean mDisableReboot = false;

    @Option(name = "reboot-wait-time", description =
            "Additional wait time in ms after boot complete.")
    private int mRebootWaitTimeMSec = 2 * 60 * 1000;

    @Option(name = "reboot-interval", description =
            "Interval between each reboot in min.")
    private int mRebootIntervalMin = 30;

    @Option(name = "screenshot-on-failure", description =
            "take a screenshot on every test failure.")
    private boolean mScreenshotOnFailures = false;

    @Option(name = "logcat-on-failure", description =
            "take a logcat snapshot on every test failure. Unlike --bugreport, this can capture" +
            "logs even if connection with device has been lost, as well as being much more " +
            "performant.")
    private boolean mLogcatOnFailures = false;

    @Option(name = AbiFormatter.FORCE_ABI_STRING,
            description = AbiFormatter.FORCE_ABI_DESCRIPTION,
            importance = Importance.IF_UNSET)
    private String mForceAbi = null;

    @Option(name = "logcat-on-failure-size", description =
            "The max number of logcat data in bytes to capture when --logcat-on-failure is on. " +
            "Should be an amount that can comfortably fit in memory.")
    private int mMaxLogcatBytes = 500 * 1024; // 500K

    @Option(name = "collect-deqp-logs", description =
            "Collect dEQP logs from the device.")
    private boolean mCollectDeqpLogs = false;

    private long mPrevRebootTime; // last reboot time

    /** data structure for a {@link IRemoteTest} and its known tests */
    class TestPackage {
        private final IRemoteTest mTestForPackage;
        private final ITestPackageDef mPackageDef;
        private final Collection<TestIdentifier> mKnownTests;

        TestPackage(ITestPackageDef packageDef, IRemoteTest testForPackage,
                Collection<TestIdentifier> knownTests) {
            mPackageDef = packageDef;
            mTestForPackage = testForPackage;
            mKnownTests = knownTests;
        }

        IRemoteTest getTestForPackage() {
            return mTestForPackage;
        }

        Collection<TestIdentifier> getKnownTests() {
            return mKnownTests;
        }

        ITestPackageDef getPackageDef() {
            return mPackageDef;
        }

        /**
         * @return the test run name that should be used for the TestPackage.
         */
        String getTestRunName() {
            return mPackageDef.getId();
        }

        /**
         * @return the ABI on which the test will run.
         */
        IAbi getAbi() {
            return mPackageDef.getAbi();
        }
    }

    /**
     * A {@link ResultForwarder} that will forward a bugreport on each failed test.
     */
    private static class FailedTestBugreportGenerator extends ResultForwarder {
        private ITestDevice mDevice;

        public FailedTestBugreportGenerator(ITestInvocationListener listener, ITestDevice device) {
            super(listener);
            mDevice = device;
        }

        @Override
        public void testFailed(TestIdentifier test, String trace) {
            super.testFailed(test, trace);
            InputStreamSource bugSource = mDevice.getBugreport();
            super.testLog(String.format("bug-%s_%s", test.getClassName(), test.getTestName()),
                    LogDataType.TEXT, bugSource);
            bugSource.cancel();
        }
    }

    /**
     * A {@link ResultForwarder} that will forward a logcat snapshot on each failed test.
     */
    private static class FailedTestLogcatGenerator extends ResultForwarder {
        private ITestDevice mDevice;
        private int mNumLogcatBytes;

        public FailedTestLogcatGenerator(ITestInvocationListener listener, ITestDevice device,
                int maxLogcatBytes) {
            super(listener);
            mDevice = device;
            mNumLogcatBytes = maxLogcatBytes;
        }

        @Override
        public void testFailed(TestIdentifier test, String trace) {
            super.testFailed(test, trace);
            // sleep a small amount of time to ensure test failure stack trace makes it into logcat
            // capture
            RunUtil.getDefault().sleep(10);
            InputStreamSource logSource = mDevice.getLogcat(mNumLogcatBytes);
            super.testLog(String.format("logcat-%s_%s", test.getClassName(), test.getTestName()),
                    LogDataType.TEXT, logSource);
            logSource.cancel();
        }
    }

    /**
     * A {@link ResultForwarder} that will forward a screenshot on test failures.
     */
    private static class FailedTestScreenshotGenerator extends ResultForwarder {
        private ITestDevice mDevice;

        public FailedTestScreenshotGenerator(ITestInvocationListener listener,
                ITestDevice device) {
            super(listener);
            mDevice = device;
        }

        @Override
        public void testFailed(TestIdentifier test, String trace) {
            super.testFailed(test, trace);

            try {
                InputStreamSource screenSource = mDevice.getScreenshot();
                super.testLog(String.format("screenshot-%s_%s", test.getClassName(),
                        test.getTestName()), LogDataType.PNG, screenSource);
                screenSource.cancel();
            } catch (DeviceNotAvailableException e) {
                // TODO: rethrow this somehow
                CLog.e("Device %s became unavailable while capturing screenshot, %s",
                        mDevice.getSerialNumber(), e.toString());
            }
        }
    }

    /** list of remaining tests to execute */
    private List<TestPackage> mRemainingTestPkgs = null;

    private CtsBuildHelper mCtsBuild = null;
    private IBuildInfo mBuildInfo = null;

    /**
     * {@inheritDoc}
     */
    @Override
    public ITestDevice getDevice() {
        return mDevice;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setDevice(ITestDevice device) {
        mDevice = device;
    }

    /**
     * Set the plan name to run.
     * <p/>
     * Exposed for unit testing
     */
    void setPlanName(String planName) {
        mPlanName = planName;
    }

    /**
     * Set the skip collect device info flag.
     * <p/>
     * Exposed for unit testing
     */
    void setSkipDeviceInfo(boolean skipDeviceInfo) {
        mSkipDeviceInfo = skipDeviceInfo;
    }

    /**
     * Adds a package name to the list of test packages to run.
     * <p/>
     * Exposed for unit testing
     */
    void addPackageName(String packageName) {
        mPackageNames.add(packageName);
    }

    /**
     * Adds a package name to the list of test packages to exclude.
     * <p/>
     * Exposed for unit testing
     */
    void addExcludedPackageName(String packageName) {
        mExcludedPackageNames.add(packageName);
    }

    /**
     * Set the test class name to run.
     * <p/>
     * Exposed for unit testing
     */
    void setClassName(String className) {
        mClassName = className;
    }

    /**
     * Set the test method name to run.
     * <p/>
     * Exposed for unit testing
     */
    void setMethodName(String methodName) {
        mMethodName = methodName;
    }

    /**
     * Set the test name to run e.g. android.test.cts.SampleTest#testSample
     * <p/>
     * Exposed for unit testing
     */
    void setTestName(String testName) {
        mTestName = testName;
    }

    /**
     * Sets the test session id to continue.
     * <p/>
     * Exposed for unit testing
     */
     void setContinueSessionId(int sessionId) {
        mContinueSessionId = sessionId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isResumable() {
        return mResume;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setBuild(IBuildInfo build) {
        mCtsBuild = CtsBuildHelper.createBuildHelper(build);
        mBuildInfo = build;
    }

    /**
     * Set the CTS build container.
     * <p/>
     * Exposed so unit tests can mock the provided build.
     *
     * @param buildHelper
     */
    void setBuildHelper(CtsBuildHelper buildHelper) {
        mCtsBuild = buildHelper;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run(ITestInvocationListener listener) throws DeviceNotAvailableException {
        if (getDevice() == null) {
            throw new IllegalArgumentException("missing device");
        }

        if (mRemainingTestPkgs == null) {
            checkFields();
            mRemainingTestPkgs = buildTestsToRun();
        }
        if (mBugreport) {
            FailedTestBugreportGenerator bugListener = new FailedTestBugreportGenerator(listener,
                    getDevice());
            listener = bugListener;
        }
        if (mScreenshotOnFailures) {
            FailedTestScreenshotGenerator screenListener = new FailedTestScreenshotGenerator(
                    listener, getDevice());
            listener = screenListener;
        }
        if (mLogcatOnFailures) {
            FailedTestLogcatGenerator logcatListener = new FailedTestLogcatGenerator(
                    listener, getDevice(), mMaxLogcatBytes);
            listener = logcatListener;
        }

        // Only run test packages on ABIs which this device supports.
        Set<String> abis = getAbis();
        if (abis == null || abis.isEmpty()) {
            throw new IllegalArgumentException("could not get device's ABIs");
        }
        List<TestPackage> packages = filterTestPackagesByAbi(mRemainingTestPkgs, abis);

        // collect and install the prerequisiteApks first, to save time when multiple test
        // packages are using the same prerequisite apk (I'm looking at you, CtsTestStubs!)
        Collection<String> prerequisiteApks = getPrerequisiteApks(packages);
        Collection<String> uninstallPackages = getPrerequisitePackageNames(packages);
        List<ResultFilter> filters = new ArrayList<ResultFilter>(packages.size());

        try {
            installPrerequisiteApks(prerequisiteApks);

            // always collect the device info, even for resumed runs, since test will likely be
            // running on a different device
            collectDeviceInfo(getDevice(), mCtsBuild, listener);
            if (packages.size() > 1 && !mDisableReboot) {
                Log.i(LOG_TAG, "Initial reboot for multiple packages");
                rebootDevice();
            }
            mPrevRebootTime = System.currentTimeMillis();

            while (!packages.isEmpty()) {
                TestPackage knownTests = packages.get(0);
                ResultFilter filter = new ResultFilter(listener, knownTests);
                filters.add(filter);

                IRemoteTest test = knownTests.getTestForPackage();
                if (test instanceof IBuildReceiver) {
                    ((IBuildReceiver) test).setBuild(mBuildInfo);
                }
                if (test instanceof IDeviceTest) {
                    ((IDeviceTest) test).setDevice(getDevice());
                }
                if (test instanceof DeqpTestRunner) {
                    ((DeqpTestRunner)test).setCollectLogs(mCollectDeqpLogs);
                }

                forwardPackageDetails(knownTests.getPackageDef(), listener);
                test.run(filter);
                packages.remove(0);
                if (packages.size() > 0) {
                    rebootIfNecessary(knownTests, packages.get(0));
                    // remove artifacts like status bar from the previous test.
                    // But this cannot dismiss dialog popped-up.
                    changeToHomeScreen();
                }
            }

            if (mScreenshot) {
                InputStreamSource screenshotSource = getDevice().getScreenshot();
                try {
                    listener.testLog("screenshot", LogDataType.PNG, screenshotSource);
                } finally {
                    screenshotSource.cancel();
                }
            }

            uninstallPrequisiteApks(uninstallPackages);

        } catch (RuntimeException e) {
            CLog.e(e);
            throw e;
        } catch (Error e) {
            CLog.e(e);
            throw e;
        } finally {
            for (ResultFilter filter : filters) {
                filter.reportUnexecutedTests();
            }
        }
    }

    /**
     * @param packages The package list to filter
     * @param abis The ABIs to test on
     * @return A list of {@link TestPackage} which test one of the given ABIs
     */
    private static List<TestPackage> filterTestPackagesByAbi(
            List<TestPackage> packages, Set<String> abis){
        List<TestPackage> testPackages = new LinkedList<>();
        for (TestPackage test : packages) {
            if (abis.contains(test.getAbi().getName())) {
                testPackages.add(test);
            }
        }
        return testPackages;
    }

    private void rebootIfNecessary(TestPackage testFinished, TestPackage testToRun)
            throws DeviceNotAvailableException {
        // If there comes spurious failure like INJECT_EVENTS for a package,
        // reboot it before running it.
        // Also reboot after package which is know to leave pop-up behind
        final List<String> rebootAfterList = Arrays.asList(
                "CtsMediaTestCases",
                "CtsAccessibilityTestCases");
        final List<String> rebootBeforeList = Arrays.asList(
                "CtsAnimationTestCases",
                "CtsGraphicsTestCases",
                "CtsViewTestCases",
                "CtsWidgetTestCases" );
        long intervalInMSec = mRebootIntervalMin * 60 * 1000;
        if (mDisableReboot || mDevice.getSerialNumber().startsWith("emulator-")) {
            return;
        }
        long currentTime = System.currentTimeMillis();
        if (((currentTime - mPrevRebootTime) > intervalInMSec) ||
                rebootAfterList.contains(testFinished.getPackageDef().getName()) ||
                rebootBeforeList.contains(testToRun.getPackageDef().getName()) ) {
            Log.i(LOG_TAG,
                    String.format("Rebooting after running package %s, before package %s",
                            testFinished.getPackageDef().getName(),
                            testToRun.getPackageDef().getName()));
            rebootDevice();
            mPrevRebootTime = System.currentTimeMillis();
        }
    }

    private void rebootDevice() throws DeviceNotAvailableException {
        final int TIMEOUT_MS = 10 * 60 * 1000;
        TestDeviceOptions options = mDevice.getOptions();
        // store default value and increase time-out for reboot
        int rebootTimeout = options.getRebootTimeout();
        long onlineTimeout = options.getOnlineTimeout();
        options.setRebootTimeout(TIMEOUT_MS);
        options.setOnlineTimeout(TIMEOUT_MS);
        mDevice.setOptions(options);

        mDevice.reboot();

        // restore default values
        options.setRebootTimeout(rebootTimeout);
        options.setOnlineTimeout(onlineTimeout);
        mDevice.setOptions(options);
        Log.i(LOG_TAG, "Rebooting done");
        try {
            Thread.sleep(mRebootWaitTimeMSec);
        } catch (InterruptedException e) {
            Log.i(LOG_TAG, "Boot wait interrupted");
        }
    }

    private void changeToHomeScreen() throws DeviceNotAvailableException {
        final String homeCmd = "input keyevent 3";

        mDevice.executeShellCommand(homeCmd);
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            //ignore
        }
    }
    /**
     * Build the list of test packages to run
     */
    private List<TestPackage> buildTestsToRun() {
        List<TestPackage> testPkgList = new LinkedList<TestPackage>();
        try {
            ITestPackageRepo testRepo = createTestCaseRepo();
            Collection<ITestPackageDef> testPkgDefs = getTestPackagesToRun(testRepo);
            for (ITestPackageDef testPkgDef : testPkgDefs) {
                addTestPackage(testPkgList, testPkgDef);
            }
            if (testPkgList.isEmpty()) {
                Log.logAndDisplay(LogLevel.WARN, LOG_TAG, "No tests to run");
            }
        } catch (FileNotFoundException e) {
            throw new IllegalArgumentException("failed to find CTS plan file", e);
        } catch (ParseException e) {
            throw new IllegalArgumentException("failed to parse CTS plan file", e);
        } catch (ConfigurationException e) {
            throw new IllegalArgumentException("failed to process arguments", e);
        }
        return testPkgList;
    }

    /**
     * Adds a test package to the list of packages to test
     *
     * @param testList
     * @param testPkgDef
     */
    private void addTestPackage(List<TestPackage> testList, ITestPackageDef testPkgDef) {
        IRemoteTest testForPackage = testPkgDef.createTest(mCtsBuild.getTestCasesDir());
        if (testForPackage != null) {
            Collection<TestIdentifier> knownTests = testPkgDef.getTests();
            testList.add(new TestPackage(testPkgDef, testForPackage, knownTests));
        }
    }

    /**
     * Return the list of test package defs to run
     *
     * @return the list of test package defs to run
     * @throws ParseException
     * @throws FileNotFoundException
     * @throws ConfigurationException
     */
    private Collection<ITestPackageDef> getTestPackagesToRun(ITestPackageRepo testRepo)
            throws ParseException, FileNotFoundException, ConfigurationException {
        // use LinkedHashSet to have predictable iteration order
        Set<ITestPackageDef> testPkgDefs = new LinkedHashSet<ITestPackageDef>();
        if (mPlanName != null) {
            Log.i(LOG_TAG, String.format("Executing CTS test plan %s", mPlanName));
            File ctsPlanFile = mCtsBuild.getTestPlanFile(mPlanName);
            ITestPlan plan = createPlan(mPlanName);
            plan.parse(createXmlStream(ctsPlanFile));
            for (String id : plan.getTestIds()) {
                if (!mExcludedPackageNames.contains(AbiUtils.parseId(id)[1])) {
                    ITestPackageDef testPackageDef = testRepo.getTestPackage(id);
                    if (testPackageDef != null) {
                        testPackageDef.setTestFilter(plan.getTestFilter(id));
                        testPkgDefs.add(testPackageDef);
                    } else {
                        CLog.e("Could not find test package id %s referenced in plan %s", id,
                                mPlanName);
                    }
                }
            }
        } else if (mPackageNames.size() > 0){
            Log.i(LOG_TAG, String.format("Executing CTS test packages %s", mPackageNames));
            for (String name : mPackageNames) {
                Set<ITestPackageDef> testPackages = testRepo.getTestPackages(name);
                if (!testPackages.isEmpty()) {
                    testPkgDefs.addAll(testPackages);
                } else {
                    throw new IllegalArgumentException(String.format(
                            "Could not find test package %s. " +
                            "Use 'list packages' to see available packages." , name));
                }
            }
        } else if (mClassName != null) {
            Log.i(LOG_TAG, String.format("Executing CTS test class %s", mClassName));
            testPkgDefs.addAll(buildTestPackageDefSet(testRepo, mClassName, mMethodName));
        } else if (mTestName != null) {
            Log.i(LOG_TAG, String.format("Executing CTS test %s", mTestName));
            String [] split = mTestName.split("#");
            if (split.length != 2) {
                Log.logAndDisplay(LogLevel.WARN, LOG_TAG, String.format(
                        "Could not parse class and method from test %s", mTestName));
            } else {
                String className = split[0];
                String methodName = split[1];
                testPkgDefs.addAll(buildTestPackageDefSet(testRepo, className, methodName));
            }
        } else if (mContinueSessionId != null) {
            // create an in-memory derived plan that contains the notExecuted tests from previous
            // session use timestamp as plan name so it will hopefully be unique
            String uniquePlanName = Long.toString(System.currentTimeMillis());
            PlanCreator planCreator = new PlanCreator(uniquePlanName, mContinueSessionId,
                    CtsTestStatus.NOT_EXECUTED);
            ITestPlan plan = createPlan(planCreator);
            for (String id : plan.getTestIds()) {
                if (!mExcludedPackageNames.contains(AbiUtils.parseId(id)[1])) {
                    ITestPackageDef testPackageDef = testRepo.getTestPackage(id);
                    if (testPackageDef != null) {
                        testPackageDef.setTestFilter(plan.getTestFilter(id));
                        testPkgDefs.add(testPackageDef);
                    }
                }
            }
        } else {
            // should never get here - was checkFields() not called?
            throw new IllegalStateException("nothing to run?");
        }
        return testPkgDefs;
    }

    /**
     * Return the list of unique prerequisite Android package names
     * @param testPackages
     */
    private Collection<String> getPrerequisitePackageNames(List<TestPackage> testPackages) {
        Set<String> pkgNames = new HashSet<String>();
        for (TestPackage testPkg : testPackages) {
            String pkgName = testPkg.mPackageDef.getTargetPackageName();
            if (pkgName != null) {
                pkgNames.add(pkgName);
            }
        }
        return pkgNames;
    }

    /**
     * @return a {@link Set} containing {@ITestPackageDef}s pertaining to the given
     *     {@code className} and {@code methodName}.
     */
    private static Set<ITestPackageDef> buildTestPackageDefSet(
            ITestPackageRepo testRepo, String className, String methodName) {
        Set<ITestPackageDef> testPkgDefs = new LinkedHashSet<ITestPackageDef>();
        // try to find packages to run from class name
        List<String> packageIds = testRepo.findPackageIdsForTest(className);
        if (packageIds.isEmpty()) {
            Log.logAndDisplay(LogLevel.WARN, LOG_TAG, String.format(
                    "Could not find package for test class %s", className));
        }
        for (String packageId: packageIds) {
            ITestPackageDef testPackageDef = testRepo.getTestPackage(packageId);
            if (testPackageDef != null) {
                testPackageDef.setClassName(className, methodName);
                testPkgDefs.add(testPackageDef);
            }
        }
        return testPkgDefs;
    }

    /**
     * Return the list of unique prerequisite apks to install
     * @param testPackages
     */
    private Collection<String> getPrerequisiteApks(List<TestPackage> testPackages) {
        Set<String> apkNames = new HashSet<String>();
        for (TestPackage testPkg : testPackages) {
            String apkName = testPkg.mPackageDef.getTargetApkName();
            if (apkName != null) {
                apkNames.add(apkName);
            }
        }
        return apkNames;
    }

    /**
     * FIXME eventually this should be removed once we get rid of CtsTestStubs, any other
     * prerequisite apks should be installed by the test runner
     *
     * Install the collection of test apk file names
     *
     * @param prerequisiteApks
     * @throws DeviceNotAvailableException
     */
    private void installPrerequisiteApks(Collection<String> prerequisiteApks)
            throws DeviceNotAvailableException {
        Log.logAndDisplay(LogLevel.INFO, LOG_TAG, "Installing prerequisites");
        Set<String> supportedAbiSet = getAbis();
        for (String apkName : prerequisiteApks) {
            try {
                File apkFile = mCtsBuild.getTestApp(apkName);
                // As a workaround for multi arch support, try to install the APK
                // for all device supported ABIs. This will generate warning messages
                // until the above FIXME is resolved.
                int installFailCount = 0;
                for (String abi : supportedAbiSet) {
                    String[] options = {AbiUtils.createAbiFlag(abi)};
                    String errorCode = getDevice().installPackage(apkFile, true, options);
                    if (errorCode != null) {
                        installFailCount++;
                        CLog.w("Failed to install %s. Reason: %s", apkName, errorCode);
                    }

                }
                if (installFailCount >= supportedAbiSet.size()) {
                    CLog.e("Failed to install %s. See warning messages.", apkName);
                }
            } catch (FileNotFoundException e) {
                CLog.e("Could not find test apk %s", apkName);
            }
        }
    }

    /**
     * Uninstalls the collection of android package names from device.
     *
     * @param uninstallPackages
     */
    private void uninstallPrequisiteApks(Collection<String> uninstallPackages)
            throws DeviceNotAvailableException {
        for (String pkgName : uninstallPackages) {
            getDevice().uninstallPackage(pkgName);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<IRemoteTest> split() {
        if (mShards <= 1) {
            return null;
        }
        checkFields();
        List<TestPackage> allTests = buildTestsToRun();

        if (allTests == null || allTests.size() <= 1) {
            Log.w(LOG_TAG, "no tests to shard!");
            return null;
        }

        // treat shardQueue as a circular queue, to sequentially distribute tests among shards
        Queue<IRemoteTest> shardQueue = new LinkedList<IRemoteTest>();
        // don't create more shards than the number of tests we have!
        for (int i = 0; i < mShards && i < allTests.size(); i++) {
            CtsTest shard = new CtsTest();
            OptionCopier.copyOptionsNoThrow(this, shard);
            shard.mShards = 0;
            shard.mRemainingTestPkgs = new LinkedList<TestPackage>();
            shardQueue.add(shard);
        }
        while (!allTests.isEmpty()) {
            TestPackage testPair = allTests.remove(0);
            CtsTest shard = (CtsTest)shardQueue.poll();
            shard.mRemainingTestPkgs.add(testPair);
            shardQueue.add(shard);
        }
        return shardQueue;
    }

    /**
     * Runs the device info collector instrumentation on device, and forwards it to test listeners
     * as run metrics.
     * <p/>
     * Exposed so unit tests can mock.
     *
     * @throws DeviceNotAvailableException
     */
    void collectDeviceInfo(ITestDevice device, CtsBuildHelper ctsBuild,
            ITestInvocationListener listener) throws DeviceNotAvailableException {
        if (!mSkipDeviceInfo) {
            String abi = AbiFormatter.getDefaultAbi(device, "");
            DeviceInfoCollector.collectDeviceInfo(device, abi, ctsBuild.getTestCasesDir(), listener);
        }
    }

    /**
     * Factory method for creating a {@link ITestPackageRepo}.
     * <p/>
     * Exposed for unit testing
     */
    ITestPackageRepo createTestCaseRepo() {
        return new TestPackageRepo(mCtsBuild.getTestCasesDir(), AbiUtils.getAbisSupportedByCts(),
                mIncludeKnownFailures);
    }

    /**
     * Factory method for creating a {@link TestPlan}.
     * <p/>
     * Exposed for unit testing
     */
    ITestPlan createPlan(String planName) {
        return new TestPlan(planName, AbiUtils.getAbisSupportedByCts());
    }

    /**
     * Gets the set of ABIs supported by both CTS and the device under test
     * <p/>
     * Exposed for unit testing
     * @return The set of ABIs to run the tests on
     * @throws DeviceNotAvailableException
     */
    Set<String> getAbis() throws DeviceNotAvailableException {
        String bitness = (mForceAbi == null) ? "" : mForceAbi;
        Set<String> abis = new HashSet<String>();
        for (String abi : AbiFormatter.getSupportedAbis(mDevice, bitness)) {
            if (AbiUtils.isAbiSupportedByCts(abi)) {
                abis.add(abi);
            }
        }
        Log.logAndDisplay(LogLevel.INFO, LOG_TAG, "ABIs: " + abis);
        return abis;
    }

    /**
     * Factory method for creating a {@link TestPlan} from a {@link PlanCreator}.
     * <p/>
     * Exposed for unit testing
     * @throws ConfigurationException
     */
    ITestPlan createPlan(PlanCreator planCreator)
            throws ConfigurationException {
        return planCreator.createDerivedPlan(mCtsBuild, AbiUtils.getAbisSupportedByCts());
    }

    /**
     * Factory method for creating a {@link InputStream} from a plan xml file.
     * <p/>
     * Exposed for unit testing
     */
    InputStream createXmlStream(File xmlFile) throws FileNotFoundException {
        return new BufferedInputStream(new FileInputStream(xmlFile));
    }

    private void checkFields() {
        // for simplicity of command line usage, make --plan, --package, --test and --class mutually
        // exclusive
        boolean mutualExclusiveArgs = xor(mPlanName != null, mPackageNames.size() > 0,
                mClassName != null, mContinueSessionId != null, mTestName != null);

        if (!mutualExclusiveArgs) {
            throw new IllegalArgumentException(String.format(
                    "Ambiguous or missing arguments. " +
                    "One and only one of --%s --%s(s), --%s or --%s to run can be specified",
                    PLAN_OPTION, PACKAGE_OPTION, CLASS_OPTION, CONTINUE_OPTION));
        }
        if (mMethodName != null && mClassName == null) {
            throw new IllegalArgumentException(String.format(
                    "Must specify --%s when --%s is used", CLASS_OPTION, METHOD_OPTION));
        }
        if (mCtsBuild == null) {
            throw new IllegalArgumentException("missing CTS build");
        }
    }

    /**
     * Helper method to perform exclusive or on list of boolean arguments
     *
     * @param args set of booleans on which to perform exclusive or
     * @return <code>true</code> if one and only one of <var>args</code> is <code>true</code>.
     *         Otherwise return <code>false</code>.
     */
    private boolean xor(boolean... args) {
        boolean currentVal = args[0];
        for (int i=1; i < args.length; i++) {
            if (currentVal && args[i]) {
                return false;
            }
            currentVal |= args[i];
        }
        return currentVal;
    }

    /**
     * Forward the digest and package name to the listener as a metric
     *
     * @param listener
     */
    private void forwardPackageDetails(ITestPackageDef def, ITestInvocationListener listener) {
        Map<String, String> metrics = new HashMap<String, String>(3);
        metrics.put(PACKAGE_NAME_METRIC, def.getName());
        metrics.put(PACKAGE_ABI_METRIC, def.getAbi().getName());
        metrics.put(PACKAGE_DIGEST_METRIC, def.getDigest());
        listener.testRunStarted(def.getId(), 0);
        listener.testRunEnded(0, metrics);
    }
}
