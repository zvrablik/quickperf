/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * Copyright 2019-2019 the original author or authors.
 */

package org.quickperf.junit4;

import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;
import org.quickperf.*;
import org.quickperf.annotation.DisplayAppliedAnnotations;
import org.quickperf.config.library.QuickPerfConfigLoader;
import org.quickperf.config.SpecifiableGlobalAnnotations;
import org.quickperf.config.library.QuickPerfConfigs;
import org.quickperf.config.library.SetOfAnnotationConfigs;
import org.quickperf.measure.PerfMeasure;
import org.quickperf.perfrecording.PerfRecord;
import org.quickperf.perfrecording.RecordablePerformance;
import org.quickperf.perfrecording.ViewablePerfRecordIfPerfIssue;
import org.quickperf.reporter.ConsoleReporter;
import org.quickperf.testlauncher.NewJvmTestLauncher;

import java.lang.annotation.Annotation;
import java.util.*;

public class MainJvmAfterJUnitStatement extends Statement {

    private final FrameworkMethod frameworkMethod;

    private final TestExecutionContext testExecutionContext;

    private final SetOfAnnotationConfigs testAnnotationConfigs;

    private final Statement junitAfters;

    private final IssueThrower issueThrower = IssueThrower.INSTANCE;

    private final NewJvmTestLauncher newJvmTestLauncher = NewJvmTestLauncher.INSTANCE;

    private final JUnit4FailuresRepository jUnit4FailuresRepository = JUnit4FailuresRepository.getInstance();

    public MainJvmAfterJUnitStatement(
              FrameworkMethod frameworkMethod
            , TestExecutionContext testExecutionContext
            , QuickPerfConfigs quickPerfConfigs
            , Statement junitAfters) {
        this.testExecutionContext = testExecutionContext;
        this.frameworkMethod = frameworkMethod;
        this.testAnnotationConfigs = quickPerfConfigs.getTestAnnotationConfigs();
        this.junitAfters = junitAfters;
    }

    @Override
    public void evaluate() throws Throwable {

        Throwable businessThrowable = null;

        if (testExecutionContext.testExecutionUsesTwoJVMs()) {
            newJvmTestLauncher.run( frameworkMethod.getMethod()
                                  , testExecutionContext.getWorkingFolder()
                                  , testExecutionContext.getJvmOptions()
                                  , QuickPerfJunit4Core.class);
            WorkingFolder workingFolder = testExecutionContext.getWorkingFolder();
            businessThrowable = jUnit4FailuresRepository.find(workingFolder);
        } else {
            // Run test in same jvm
            try {
                junitAfters.evaluate();
            } catch (Throwable throwable) {
                businessThrowable = throwable;
            }
        }

        Map<Annotation, PerfRecord> perfRecordByAnnotation
                = buildPerfRecordByAnnotation(testAnnotationConfigs);

        Map<Annotation, PerfIssue> perfIssuesByAnnotation
                = evaluatePerfIssuesByAnnotation(perfRecordByAnnotation);

        Collection<PerfIssuesToFormat> groupOfPerfIssuesToFormat
                = perfIssuesToFormatGroup(perfRecordByAnnotation, perfIssuesByAnnotation);

        cleanResources();

        if(testExecutionContext.areQuickPerfAnnotationsToBeDisplayed()) {
            ConsoleReporter.displayQuickPerfAnnotations(testExecutionContext.getPerfAnnotations());
        }

        if (testExecutionContext.isQuickPerfDebugMode()) {
            ConsoleReporter.displayQuickPerfDebugInfos();
        }

        issueThrower.throwIfNecessary(businessThrowable, groupOfPerfIssuesToFormat);

    }

    private Map<Annotation, PerfIssue> evaluatePerfIssuesByAnnotation(Map<Annotation, PerfRecord> perfRecordByAnnotation) {
        Map<Annotation, PerfMeasure> perfMeasureByAnnotation
                = extractPerfMeasureByAnnotation(testAnnotationConfigs, perfRecordByAnnotation);
        return evaluatePerfIssuesByAnnotation(perfMeasureByAnnotation
                                            , testAnnotationConfigs);
    }

    private Map<Annotation, PerfRecord> buildPerfRecordByAnnotation(SetOfAnnotationConfigs testAnnotationConfigs) {
        Map<Annotation, PerfRecord> perfRecordByAnnotation =new HashMap<>();
        Map<Class<? extends RecordablePerformance>, RecordablePerformance> perfRecorderByPerfRecorderClass = buildPerfRecorderInstanceByPerfRecorderClass();
        for (Annotation annotation : testExecutionContext.getPerfAnnotations()) {
            Class<? extends RecordablePerformance> perfRecorderClass = testAnnotationConfigs.retrievePerfRecorderClassFor(annotation);
            RecordablePerformance perfRecorder = perfRecorderByPerfRecorderClass.get(perfRecorderClass);
            if (perfRecorder != null) {
                PerfRecord perfRecord = findPerfRecord(perfRecorder);
                perfRecordByAnnotation.put(annotation, perfRecord);

            }
        }
        return perfRecordByAnnotation;
    }

    private PerfRecord findPerfRecord(RecordablePerformance perfRecorder) {
        try {
            return perfRecorder.findRecord(testExecutionContext);
        } catch (Exception e) {
            WorkingFolder workingFolder = testExecutionContext.getWorkingFolder();
            Throwable throwableFromTestJvm = jUnit4FailuresRepository.find(workingFolder);
            if(throwableFromTestJvm != null) {
               e.addSuppressed(throwableFromTestJvm);
            }
            throw e;
        }
    }

    private Collection<PerfIssuesToFormat> perfIssuesToFormatGroup(
              Map<Annotation, PerfRecord> perfRecordByAnnotation
            , Map<Annotation, PerfIssue> perfIssuesByAnnotation) {
        List<PerfIssuesToFormat> perfIssuesToFormatGroup = new ArrayList<>();
        Map<PerfRecord, List<PerfIssue>> perfIssuesByPerfRecord = buildPerfIssuesByPerfRecord(perfRecordByAnnotation, perfIssuesByAnnotation);
        for (PerfRecord perfRecord : perfIssuesByPerfRecord.keySet()) {
            List<PerfIssue> perfIssues = perfIssuesByPerfRecord.get(perfRecord);
            PerfIssuesFormat perfIssuesFormat = retrievePerfIssuesFormat(perfRecord);
            PerfIssuesToFormat perfIssuesToFormat = new PerfIssuesToFormat(perfIssues, perfIssuesFormat);
            perfIssuesToFormatGroup.add(perfIssuesToFormat);
        }
        return perfIssuesToFormatGroup;
    }

    private PerfIssuesFormat retrievePerfIssuesFormat(PerfRecord perfRecord) {
        if (perfRecord instanceof PerfIssuesFormat) {
            return (PerfIssuesFormat) perfRecord;
        }
        return ViewablePerfRecordIfPerfIssue.STANDARD;
    }

    private Map<PerfRecord, List<PerfIssue>> buildPerfIssuesByPerfRecord(Map<Annotation, PerfRecord> perfRecordByAnnotation, Map<Annotation, PerfIssue> perfIssuesByAnnotation) {
        Map<PerfRecord, List<PerfIssue>> perfIssuesByPerfRecord = new HashMap<>();
        for (Annotation annotation : perfRecordByAnnotation.keySet()) {

            PerfRecord perfRecord = perfRecordByAnnotation.get(annotation);

            List<PerfIssue> perfIssues = perfIssuesByPerfRecord.get(perfRecord);
            if(perfIssues == null) {
                perfIssues = new ArrayList<>();
            }
            PerfIssue perfIssue = perfIssuesByAnnotation.get(annotation);
            if(perfIssue != null) {
                perfIssues.add(perfIssue);
            }
            if(!perfIssues.isEmpty()) {
                perfIssuesByPerfRecord.put(perfRecord, perfIssues);
            }
        }
        return perfIssuesByPerfRecord;
    }

    private void cleanResources() {
        List<RecordablePerformance> perfRecorders = testExecutionContext.getPerfRecordersToExecuteAfterTestMethod();
        for (RecordablePerformance perfRecorder : perfRecorders) {
            perfRecorder.cleanResources();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<Annotation, PerfMeasure> extractPerfMeasureByAnnotation(SetOfAnnotationConfigs testAnnotationConfigs, Map<Annotation, PerfRecord> perfRecordByAnnotation) {
        Map<Annotation, PerfMeasure> perfMeasureByAnnotation = new HashMap<>();
            for (Annotation annotation : testExecutionContext.getPerfAnnotations()) {
                ExtractablePerformanceMeasure perfMeasureExtractor = testAnnotationConfigs.retrievePerfMeasureExtractorFor(annotation);
                PerfRecord perfRecord = perfRecordByAnnotation.get(annotation);
                PerfMeasure perfMeasure = perfMeasureExtractor.extractPerfMeasureFrom(perfRecord);
                if(perfMeasure != PerfMeasure.NONE) {
                    perfMeasureByAnnotation.put(annotation, perfMeasure);
                }
        }
        return perfMeasureByAnnotation;
    }

    private Map<Class<? extends RecordablePerformance>, RecordablePerformance> buildPerfRecorderInstanceByPerfRecorderClass() {
        List<RecordablePerformance> perfRecorders = testExecutionContext.getPerfRecordersToExecuteAfterTestMethod();
        Map<Class<? extends RecordablePerformance>, RecordablePerformance>
                perfRecorderInstanceByPerfRecorderClass = new HashMap<>();
        for (RecordablePerformance perfRecorder : perfRecorders) {
            perfRecorderInstanceByPerfRecorderClass.put(perfRecorder.getClass(), perfRecorder);
        }
        return perfRecorderInstanceByPerfRecorderClass;
    }

    private Map<Annotation, PerfIssue> evaluatePerfIssuesByAnnotation(
                                               Map<Annotation, PerfMeasure> perfMeasuredByAnnotation
                                             , SetOfAnnotationConfigs testAnnotationConfigs) {
        Map<Annotation, PerfIssue> perfIssueByAnnotation = new HashMap<>();
        for (Annotation annotation : perfMeasuredByAnnotation.keySet()) {
            PerfIssue perfIssue = evaluatePerfIssue(perfMeasuredByAnnotation, testAnnotationConfigs, annotation);
            if(perfIssue != PerfIssue.NONE) {
                perfIssueByAnnotation.put(annotation, perfIssue);
            }
        }
        return perfIssueByAnnotation;
    }

    @SuppressWarnings("unchecked")
    private PerfIssue evaluatePerfIssue(Map<Annotation, PerfMeasure> perfMeasuredByAnnotation
                                      , SetOfAnnotationConfigs testAnnotationConfigs
                                      , Annotation annotation) {
        PerfMeasure perfMeasure = perfMeasuredByAnnotation.get(annotation);
        VerifiablePerformanceIssue perfIssueVerifier = testAnnotationConfigs.retrievePerfIssuerVerifierFor(annotation);
        return perfIssueVerifier.verifyPerfIssue(annotation, perfMeasure);
    }

}
