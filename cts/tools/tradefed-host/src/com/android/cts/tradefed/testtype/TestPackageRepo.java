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

import com.android.cts.util.AbiUtils;
import com.android.ddmlib.Log;
import com.android.tradefed.util.xml.AbstractXmlParser.ParseException;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Retrieves CTS test package definitions from the repository.
 */
public class TestPackageRepo implements ITestPackageRepo {

    private static final String LOG_TAG = "TestCaseRepo";

    private final File mTestCaseDir;

    /** mapping of ABI to a mapping of appPackageName to test definition */
    private final Map<String, Map<String, TestPackageDef>> mTestMap;
    /** set of ABIs */
    private final Set<String> mAbis;
    private final boolean mIncludeKnownFailures;

    /**
     * Creates a {@link TestPackageRepo}, initialized from provided repo files
     *
     * @param testCaseDir directory containing all test case definition xml and build files
     * @param abis Holds the ABIs which the test must be run against. This must be a subset of the
     * ABIs supported by the device under test.
     * @param includeKnownFailures Whether to run tests which are known to fail.
     */
    public TestPackageRepo(File testCaseDir, Set<String> abis, boolean includeKnownFailures) {
        mTestCaseDir = testCaseDir;
        mTestMap = new HashMap<String, Map<String, TestPackageDef>>();
        mAbis = abis;
        for (String abi : abis) {
            mTestMap.put(abi, new HashMap<String, TestPackageDef>());
        }
        mIncludeKnownFailures = includeKnownFailures;
        parse(mTestCaseDir);
    }

    /**
     * Builds mTestMap based on directory contents
     */
    private void parse(File dir) {
        File[] xmlFiles = dir.listFiles(new XmlFilter());
        for (File xmlFile : xmlFiles) {
            parseTestFromXml(xmlFile);
        }
    }

    private void parseTestFromXml(File xmlFile)  {
        TestPackageXmlParser parser = new TestPackageXmlParser(mAbis, mIncludeKnownFailures);
        try {
            parser.parse(createStreamFromFile(xmlFile));
            Set<TestPackageDef> defs = parser.getTestPackageDefs();
            if (!defs.isEmpty()) {
                for (TestPackageDef def : defs) {
                    String name = def.getAppPackageName();
                    String abi = def.getAbi().getName();
                    if (def.getTests().size() > 0) {
                        mTestMap.get(abi).put(name, def);
                    } else {
                        Log.d(LOG_TAG, String.format("No tests in %s for %s, skipping",
                                name, abi));
                    }
                }
            } else {
                Log.w(LOG_TAG, String.format("Could not find test package info in xml file %s",
                        xmlFile.getAbsolutePath()));
            }
        } catch (FileNotFoundException e) {
            Log.e(LOG_TAG, String.format("Could not find test case xml file %s",
                    xmlFile.getAbsolutePath()));
            Log.e(LOG_TAG, e);
        } catch (ParseException e) {
            Log.e(LOG_TAG, String.format("Failed to parse test case xml file %s",
                    xmlFile.getAbsolutePath()));
            Log.e(LOG_TAG, e);
        }
    }

    /**
     * Helper method to create a stream to read data from given file
     * <p/>
     * Exposed for unit testing
     *
     * @param xmlFile
     * @return stream to read data
     *
     */
    InputStream createStreamFromFile(File xmlFile) throws FileNotFoundException {
        return new BufferedInputStream(new FileInputStream(xmlFile));
    }

    private static class XmlFilter implements FilenameFilter {

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean accept(File dir, String name) {
            return name.endsWith(".xml");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ITestPackageDef getTestPackage(String id) {
        String[] parts = AbiUtils.parseId(id);
        String abi = parts[0];
        String name = parts[1];
        if (mTestMap.containsKey(abi) && mTestMap.get(abi).containsKey(name)) {
            return mTestMap.get(abi).get(name);
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<ITestPackageDef> getTestPackages(String appPackageName) {
        Set<ITestPackageDef> defs = new HashSet<ITestPackageDef>();
        for (String abi : mAbis) {
            if (mTestMap.get(abi).containsKey(appPackageName)) {
                defs.add(mTestMap.get(abi).get(appPackageName));
            }
        }
        return defs;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> findPackageIdsForTest(String testClassName) {
        Set<String> ids = new HashSet<String>();
        for (String abi : mTestMap.keySet()) {
            for (String name : mTestMap.get(abi).keySet()) {
                if (mTestMap.get(abi).get(name).isKnownTestClass(testClassName)) {
                    ids.add(AbiUtils.createId(abi, name));
                }
            }
        }
        List<String> idList = new ArrayList<String>(ids);
        Collections.sort(idList);
        return idList;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> getPackageIds() {
        Set<String> ids = new HashSet<String>();
        for (String abi : mAbis) {
            for (String name : mTestMap.get(abi).keySet()) {
                ids.add(AbiUtils.createId(abi, name));
            }
        }
        List<String> idList = new ArrayList<String>(ids);
        Collections.sort(idList);
        return idList;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> getPackageNames() {
        Set<String> names = new HashSet<String>();
        for (String abi : mAbis) {
            names.addAll(mTestMap.get(abi).keySet());
        }
        List<String> packageNames = new ArrayList<String>(names);
        Collections.sort(packageNames);
        return packageNames;
    }
}
