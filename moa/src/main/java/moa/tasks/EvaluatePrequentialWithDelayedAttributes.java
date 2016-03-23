package moa.tasks;

import com.github.javacliparser.FileOption;
import com.github.javacliparser.FloatOption;
import com.github.javacliparser.IntOption;
import com.yahoo.labs.samoa.instances.Attribute;
import com.yahoo.labs.samoa.instances.Instance;
import com.yahoo.labs.samoa.instances.InstancesHeader;
import moa.classifiers.Classifier;
import moa.core.*;
import moa.evaluation.*;
import moa.options.ClassOption;
import moa.streams.ExampleStream;
import moa.streams.filters.DuplicateFilter;
import moa.streams.filters.SelectAttributesFilter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.*;

public class EvaluatePrequentialWithDelayedAttributes extends MainTask {

    //region Overrides
    @Override
    public String getPurposeString() {
        return "Evaluates a classifier on a stream by testing then training with each example in sequence.";
    }

    @Override
    public Class<?> getTaskResultType() {
        return LearningCurve[].class;
    }

    private static final long serialVersionUID = 1L;
    //endregion

    //region Options
    @SuppressWarnings("WeakerAccess")
    public ClassOption classifierOption = new ClassOption("classifier", 'c',
            "Classifier (learner) to train.", Classifier.class, "moa.classifiers.bayes.NaiveBayes");

    public ClassOption streamOption = new ClassOption("stream", 's',
            "Stream to learn from.", ExampleStream.class,
            "generators.RandomTreeGenerator");

    public ClassOption evaluatorOption = new ClassOption("evaluator", 'e',
            "Classification performance evaluation method.",
            LearningPerformanceEvaluator.class,
            "WindowClassificationPerformanceEvaluator");

    public IntOption instanceLimitOption = new IntOption("instanceLimit", 'i',
            "Maximum number of instances to test/train on  (-1 = no limit).",
            100000000, -1, Integer.MAX_VALUE);

    public IntOption timeLimitOption = new IntOption("timeLimit", 't',
            "Maximum number of seconds to test/train for (-1 = no limit).", -1,
            -1, Integer.MAX_VALUE);

    public IntOption sampleFrequencyOption = new IntOption("sampleFrequency",
            'f',
            "How many instances between samples of the learning performance.",
            100000, 0, Integer.MAX_VALUE);

    public IntOption memCheckFrequencyOption = new IntOption(
            "memCheckFrequency", 'q',
            "How many instances between memory bound checks.", 100000, 0,
            Integer.MAX_VALUE);

    public FileOption dumpFileOption = new FileOption("dumpFile", 'd',
            "File to append intermediate csv results to.", null, "csv", true);

    public FileOption outputPredictionFileOption = new FileOption("outputPredictionFile", 'o',
            "File to append output predictions to.", null, "pred", true);

    //New for prequential method DEPRECATED
    public IntOption widthOption = new IntOption("width",
            'w', "Size of Window", 1000);

    public FloatOption alphaOption = new FloatOption("alpha",
            'a', "Fading factor or exponential smoothing factor", .01);
    //End New for prequential methods

    //endregion

    //region Properties - For comport
    private ExampleStream<Example<Instance>> stream;
    private InstancesHeader header;
    private SelectAttributesFilter[] selectors;
    private LearningCurve[] curves;
    private Classifier[] classifiers;
    private LearningPerformanceEvaluator[] evaluators;
    private Map<Integer, Set<Integer>> attributes;
    private boolean[] isOutput;
    //endregion


    //region Methods
    private void _initialize() {
        //noinspection unchecked
        stream = (ExampleStream<Example<Instance>>) getPreparedClassOption(this.streamOption);
        header = stream.getHeader();

        _createMap();
        _createMapping();
        _createFilters();
        _createClassifiers();
        _createEvaluators();
        _createCurves();

    }

    private void _createCurves() {
        curves = new LearningCurve[classifiers.length];

        for (int i = 0; i < curves.length; i++)
            curves[i] = new LearningCurve("learning evaluation instances");
    }

    private void _createMap() {
        Set<String> output = new HashSet<>();

        int size = header.numOutputAttributes();
        for (int i = 0; i < size; i++) {
            Attribute attribute = header.outputAttribute(i);
            output.add(attribute.name());
        }

        size = header.numAttributes();
        isOutput = new boolean[size];

        for (int i = 0; i < size; i++) {
            Attribute attribute = header.attribute(i);
            isOutput[i] = output.contains(attribute.name());
        }
    }

    private void _createMapping() {
        //TODO: Check "Test -> Train" for getting the output attribute

        int size = header.numAttributes();
        attributes = new HashMap<>();

        for (int i = 0; i < size; i++) {
            if (isOutput[i])
                continue;

            Attribute a = header.attribute(i);
            int delay = a.getDelayTime();
            if (delay >= 0) {
                Set<Integer> lst;
                if (attributes.containsKey(delay)) lst = attributes.get(delay);
                else attributes.put(delay, (lst = new HashSet<Integer>()));
                lst.add(i);
            }
        }
    }

    private void _createFilters() {
        // Build the filter that duplicates the instances - tested
        DuplicateFilter copier = new DuplicateFilter(attributes.size());
        copier.setInputStream(stream);

        // Create the vector of SelectAttributesFilter
        selectors = new SelectAttributesFilter[attributes.size()];

        // Create the output string
        List<Integer> output = new LinkedList<>();
        for (int i = 0; i < isOutput.length; i++)
            if (isOutput[i])
                output.add(i + 1);

        String outputStr = makeStr(output);

        List<Integer> inputIndexes = new LinkedList<>();
        List<Integer> keys = new LinkedList<>(attributes.keySet());
        Collections.sort(keys);

        // Load the vector with filters
        for (Integer delay : keys) {
            for (Integer idx : attributes.get(delay))
                inputIndexes.add(idx + 1);

            Collections.sort(inputIndexes);
            String inputStr = makeStr(inputIndexes);

            SelectAttributesFilter filter = new SelectAttributesFilter();
            filter.setInputStream(copier);
            filter.inputStringOption.setValue(inputStr);
            filter.outputStringOption.setValue(outputStr);
            filter.prepareForUse();
            selectors[delay] = filter;
        }
    }

    private void _createClassifiers() {
        Classifier c = (Classifier) getPreparedClassOption(this.classifierOption);
        classifiers = new Classifier[selectors.length];

        for (int i = 0; i < classifiers.length; i++) {
            classifiers[i] = c.copy();
        }
    }

    private void _createEvaluators() {
        evaluators = new LearningPerformanceEvaluator[selectors.length];
        LearningPerformanceEvaluator evaluator = (LearningPerformanceEvaluator) getPreparedClassOption(this.evaluatorOption);

        for (int i = 0; i < evaluators.length; i++)
            evaluators[i] = (LearningPerformanceEvaluator<Example>) evaluator.copy();
    }

    private static String makeStr(Collection<Integer> obj) {
        String s = "";
        boolean isFirst = true;

        for (Integer i : obj) {
            s += (isFirst ? "" : ",") + i;
            isFirst = false;
        }

        return s;
    }
    //endregion

    @Override
    protected Object doMainTask(TaskMonitor monitor, ObjectRepository repository) {
        _initialize();

        Classifier learner = (Classifier) getPreparedClassOption(this.classifierOption);
        ExampleStream stream = (ExampleStream) getPreparedClassOption(this.streamOption);
        LearningPerformanceEvaluator evaluator = (LearningPerformanceEvaluator) getPreparedClassOption(this.evaluatorOption);
        LearningCurve learningCurve = new LearningCurve(
                "learning evaluation instances");


        //New for prequential methods
        if (evaluator instanceof WindowClassificationPerformanceEvaluator) {
            //((WindowClassificationPerformanceEvaluator) evaluator).setWindowWidth(widthOption.getValue());
            if (widthOption.getValue() != 1000) {
                System.out.println("DEPRECATED! Use EvaluatePrequential -e (WindowClassificationPerformanceEvaluator -w " + widthOption.getValue() + ")");
                return learningCurve;
            }
        }
        if (evaluator instanceof EWMAClassificationPerformanceEvaluator) {
            //((EWMAClassificationPerformanceEvaluator) evaluator).setalpha(alphaOption.getValue());
            if (alphaOption.getValue() != .01) {
                System.out.println("DEPRECATED! Use EvaluatePrequential -e (EWMAClassificationPerformanceEvaluator -a " + alphaOption.getValue() + ")");
                return learningCurve;
            }
        }
        if (evaluator instanceof FadingFactorClassificationPerformanceEvaluator) {
            //((FadingFactorClassificationPerformanceEvaluator) evaluator).setalpha(alphaOption.getValue());
            if (alphaOption.getValue() != .01) {
                System.out.println("DEPRECATED! Use EvaluatePrequential -e (FadingFactorClassificationPerformanceEvaluator -a " + alphaOption.getValue() + ")");
                return learningCurve;
            }
        }
        //End New for prequential methods

        //learner.setModelContext(stream.getHeader());
        int maxInstances = this.instanceLimitOption.getValue();
        long instancesProcessed = 0;
        int maxSeconds = this.timeLimitOption.getValue();
        int secondsElapsed = 0;
        monitor.setCurrentActivity("Evaluating learner...", -1.0);

        File dumpFile = this.dumpFileOption.getFile();
        PrintStream immediateResultStream = null;
        if (dumpFile != null) {
            try {
                if (dumpFile.exists()) {
                    immediateResultStream = new PrintStream(
                            new FileOutputStream(dumpFile, true), true);
                } else {
                    immediateResultStream = new PrintStream(
                            new FileOutputStream(dumpFile), true);
                }
            } catch (Exception ex) {
                throw new RuntimeException(
                        "Unable to open immediate result file: " + dumpFile, ex);
            }
        }
        //File for output predictions
        File outputPredictionFile = this.outputPredictionFileOption.getFile();
        PrintStream outputPredictionResultStream = null;
        if (outputPredictionFile != null) {
            try {
                if (outputPredictionFile.exists()) {
                    outputPredictionResultStream = new PrintStream(
                            new FileOutputStream(outputPredictionFile, true), true);
                } else {
                    outputPredictionResultStream = new PrintStream(
                            new FileOutputStream(outputPredictionFile), true);
                }
            } catch (Exception ex) {
                throw new RuntimeException(
                        "Unable to open prediction result file: " + outputPredictionFile, ex);
            }
        }
        boolean firstDump = true;
        boolean preciseCPUTiming = TimingUtils.enablePreciseTiming();
        long evaluateStartTime = TimingUtils.getNanoCPUTimeOfCurrentThread();
        long lastEvaluateStartTime = evaluateStartTime;
        double RAMHours = 0.0;


        while (stream.hasMoreInstances()
                && ((maxInstances < 0) || (instancesProcessed < maxInstances))
                && ((maxSeconds < 0) || (secondsElapsed < maxSeconds))) {

            Example[] examples = new Example[classifiers.length];
            for (int i = 0; i < classifiers.length; i++) {
                Example trainInstance = selectors[i].nextInstance();
                Example testInstance = trainInstance;
                examples[i] = trainInstance;

                Classifier classifier = classifiers[i];

                double[] prediction = classifier.getVotesForInstance(testInstance);

                if (outputPredictionFile != null) {
                    int trueClass = (int) ((Instance) trainInstance.getData()).classValue();
                    outputPredictionResultStream.println(Utils.maxIndex(prediction) + "," + trueClass);
                }

                evaluators[i].addResult(testInstance, prediction);
                classifier.trainOnInstance(trainInstance);
            }

            instancesProcessed++;
            if (instancesProcessed % this.sampleFrequencyOption.getValue() == 0
                    || stream.hasMoreInstances() == false) {
                long evaluateTime = TimingUtils.getNanoCPUTimeOfCurrentThread();
                double time = TimingUtils.nanoTimeToSeconds(evaluateTime - evaluateStartTime);
                double timeIncrement = TimingUtils.nanoTimeToSeconds(evaluateTime - lastEvaluateStartTime);
                double RAMHoursIncrement = learner.measureByteSize() / (1024.0 * 1024.0 * 1024.0); //GBs
                RAMHoursIncrement *= (timeIncrement / 3600.0); //Hours
                RAMHours += RAMHoursIncrement;
                lastEvaluateStartTime = evaluateTime;

                for (int i = 0; i < curves.length; i++) {
                    Measurement[] m = new Measurement[]{
                            new Measurement(
                                    "learning evaluation instances",
                                    instancesProcessed),
                            new Measurement(
                                    "evaluation time ("
                                            + (preciseCPUTiming ? "cpu "
                                            : "") + "seconds)",
                                    time),
                            new Measurement(
                                    "model cost (RAM-Hours)",
                                    RAMHours)
                    };

                    LearningPerformanceEvaluator eval = evaluators[i];
                    Classifier c = classifiers[i];
                    LearningEvaluation eval2 = new LearningEvaluation(m, eval, c);

                    curves[i].insertEntry(eval2);
                }

                if (immediateResultStream != null) {
                    if (firstDump) {
                        immediateResultStream.println(learningCurve.headerToString());
                        firstDump = false;
                    }
                    immediateResultStream.println(learningCurve.entryToString(learningCurve.numEntries() - 1));
                    immediateResultStream.flush();
                }
            }
            if (instancesProcessed % INSTANCES_BETWEEN_MONITOR_UPDATES == 0) {
                if (monitor.taskShouldAbort()) {
                    return null;
                }
                long estimatedRemainingInstances = stream.estimatedRemainingInstances();
                if (maxInstances > 0) {
                    long maxRemaining = maxInstances - instancesProcessed;
                    if ((estimatedRemainingInstances < 0)
                            || (maxRemaining < estimatedRemainingInstances)) {
                        estimatedRemainingInstances = maxRemaining;
                    }
                }
                monitor.setCurrentActivityFractionComplete(estimatedRemainingInstances < 0 ? -1.0
                        : (double) instancesProcessed
                        / (double) (instancesProcessed + estimatedRemainingInstances));
                if (monitor.resultPreviewRequested()) {
                    for (LearningCurve curve : curves) monitor.setLatestResultPreview(curve.copy());
                }
                secondsElapsed = (int) TimingUtils.nanoTimeToSeconds(TimingUtils.getNanoCPUTimeOfCurrentThread()
                        - evaluateStartTime);
            }
        }
        if (immediateResultStream != null) {
            immediateResultStream.close();
        }
        if (outputPredictionResultStream != null) {
            outputPredictionResultStream.close();
        }
        return curves;
    }
}