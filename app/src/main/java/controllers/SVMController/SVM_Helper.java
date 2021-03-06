package controllers.SVMController;


import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.util.Log;
import android.view.View;

import com.github.lzyzsd.circleprogress.CircleProgress;
import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import libsvm.svm;
import libsvm.svm_model;
import libsvm.svm_node;
import model.SmoothEEGResults;

import static constants.AppConstants.MEDITATION_CLASS;
import static constants.SVMConstants.BPCoe;
import static constants.SVMConstants.BPGain;
import static constants.SVMConstants.BPNumSec;
import static constants.SVMConstants.NUM_BAND;
import static constants.SVMConstants.NUM_EEG_CH;
import static constants.SVMConstants.N_START_BAND;
import static constants.SVMConstants.OVER_LAP;
import static constants.SVMConstants.SAMPLE_RATE;
import static constants.SVMConstants.WINDOW_LENGTH;
import static constants.SVMConstants.WINDOW_SHIFT;
import static constants.SVMConstants.WINDOW_SIZE;
import static constants.SVMConstants.kCompMat;
import static constants.SVMConstants.kCompMat2;
import static constants.SVMConstants.nComp;
import static constants.SVMConstants.preFilterA;
import static constants.SVMConstants.preFilterB;

public class SVM_Helper {

    static String TAG = "SVM_HELPER";

    BlockingQueue<double[]> eegBufferQueue;
    svm_model svmModel;
    Context context;
    double[][] rawEEG;
    CircleProgress pb_meditation_meter;
    Handler handler = new Handler();
    SmoothEEGResults medSm;

    GraphView graph;
    LineGraphSeries<DataPoint> mSeries;
    int graphXValue = 0;

    public void setPb_meditation_meter(CircleProgress pb_meditation_meter) {
        this.pb_meditation_meter = pb_meditation_meter;
    }

    public SVM_Helper() {


    }

    public SVM_Helper(Context context, String model) {

        eegBufferQueue = new LinkedBlockingQueue<>();
        this.context = context;
        svmLoadModel(model);
        rawEEG = new double[WINDOW_SHIFT*3][NUM_EEG_CH];
        medSm = new SmoothEEGResults();
    }

    private void svmLoadModel(String filename) {
        try {
            AssetManager am = context.getAssets();
            BufferedReader br = new BufferedReader(new InputStreamReader(am.open(filename)));
            svmModel = svm.svm_load_model(br);

        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public void setupGraph(View graph) {
        this.graph = (GraphView) graph;

        mSeries = new LineGraphSeries<>();
        this.graph.addSeries(mSeries);

    }

    public void receiveEEGPacket(double[] rawEEG) {

        double[] raw = new double[rawEEG.length];
        System.arraycopy(rawEEG, 0, raw, 0, rawEEG.length);
        eegBufferQueue.add(raw);

    }


    //Constant running waiting for EEG to reach of size to process
    public Runnable processEEG = new Runnable() {
        @Override
        public void run() {
            /*
                Artifact removal causes 11 values to become zero.
                I have tried to include a additional window so it does not end up with zeros at the back
                but the results are still not ideal. Hence, The only way to do it is to do in 3 windows
                with the middle window being the one to extract features from. This will allow the
                feature extraction to match as per trained in matlab

                3 Windows, middle window is the one to be extracted
             */
            if (eegBufferQueue.size() >= SAMPLE_RATE) {

                writeToFirstSegment(rawEEG);

                double[][] feat = rawToFeature(deep_copy_2d(rawEEG));

                display(feat);

                // Shift 1st,2nd segment to 2nd,3rd respectively
                shift(rawEEG);
            }

            handler.postDelayed(processEEG, 100);
        }
    };

    public void writeToFirstSegment(double[][] rawEEG) {

        for (int i = (int) SAMPLE_RATE; i < WINDOW_SIZE; i++) {
            double[] tempEEG = eegBufferQueue.remove().clone();

            for (int j = 0; j < rawEEG[i].length; j++) {
                rawEEG[i][j] = tempEEG[j];
            }
        }

    }

    // Shift 1st,2nd to 2nd,3rd
    public void shift(double[][] rawEEG) {

        //write from backwards
        for (int i = rawEEG.length-1; i >= WINDOW_SHIFT; i--) {
            rawEEG[i] = rawEEG[i-WINDOW_SHIFT];

        }
    }

    /**
     * Display the probability on screen
     *
     * @param feat Raw EEG processed into features.
     */
    public void display(double[][] feat) {

        //Should receive 3 features which middle one is the one being processed properly
        svm_node[] node = featuresToSVMNode(feat[1]);
        double[] prob = new double[2];
        svm.svm_predict_probability(svmModel, node, prob);
        medSm.add(prob[MEDITATION_CLASS]);


        int progress = (int) (medSm.getResult() * 100.0);
//        rawMed.setText("" + prob[MEDITATION_CLASS] * 100);
        Log.d(TAG, "Progress:" + progress);
//        Log.d(TAG, "RAW Prog:" + prob[MEDITATION_CLASS] * 100.0);

        pb_meditation_meter.setProgress(progress);
        mSeries.appendData(new DataPoint(graphXValue++, medSm.getResult()), true, 45);

    }

    /**
     * Convert raw eeg from muse to features for predict.
     *
     * @param rawEEG Raw EEG from Muse
     * @return Extracted Features from raw EEG
     */
    public double[][] rawToFeature(double[][] rawEEG) {

        double[][] fEEGData = artifactRemoval(rawEEG);
        double[][] extractedFeatures = extractFeatures(fEEGData);

        return extractedFeatures;
    }

    /**
     * Convert features to SVMNode
     * @param features features from EEG
     * @return svm_node[] array
     */
    public svm_node[] featuresToSVMNode(double[] features) {

        svm_node[] svmNode = new svm_node[features.length*2];

        int len = features.length;

        for (int i = 0; i < len; i++) {
            svmNode[i] = new svm_node();
            svmNode[i].index = i;
            svmNode[i].value = features[i];

            //supposed to use for variance for now do nothing.
            svmNode[i + len] = new svm_node();
            svmNode[i + len].index = i + len;
            svmNode[i + len].value = 0;

        }

        return svmNode;
    }

    public void filterIIR(double[] filt_b, double[] filt_a, double[][] data, int ch) {
        int Nback = filt_b.length;
        double[] prev_y = new double[Nback];
        double[] prev_x = new double[Nback];

        //step through data points
        for (int i = 0; i < data.length; i++) {
            //shift the previous outputs
            for (int j = Nback-1; j > 0; j--) {
                prev_y[j] = prev_y[j-1];
                prev_x[j] = prev_x[j-1];
            }

            //add in the new point
            prev_x[0] = data[i][ch];

            //compute the new data point
            double out = 0;
            for (int j = 0; j < Nback; j++) {
                out += filt_b[j]*prev_x[j];
                if (j > 0) {
                    out -= filt_a[j]*prev_y[j];
                }
            }

            //save output value
            prev_y[0] = out;
            data[i][ch] = (float) out;
        }
    }

    public void filterIIR(double[] filt_b, double[] filt_a, double[][][] data, int ch, int band) {
        int Nback = filt_b.length;
        double[] prev_y = new double[Nback];
        double[] prev_x = new double[Nback];

        //step through data points
        for (int i = 0; i < data.length; i++) {
            //shift the previous outputs
            for (int j = Nback - 1; j > 0; j--) {
                prev_y[j] = prev_y[j - 1];
                prev_x[j] = prev_x[j - 1];
            }

            //add in the new point
            prev_x[0] = data[i][ch][band];

            //compute the new data point
            double out = 0;
            for (int j = 0; j < Nback; j++) {
                out += filt_b[j] * prev_x[j];
                if (j > 0) {
                    out -= filt_a[j] * prev_y[j];
                }
            }

            //save output value
            prev_y[0] = out;
            data[i][ch][band] = (float) out;
        }
    }

    /**
     * Removes artifacts from raw EEG
     * @param fInEEGData Raw EEG
     * @return Raw EEG with artifact removed
     */
    public double[][] artifactRemoval(double[][] fInEEGData) {

        for (int i=0; i<NUM_EEG_CH; i++){
            filterIIR(preFilterB, preFilterA, fInEEGData, i);
        }

        // Array of fInEEGData should be 512 x 4
        double[][] fRefData = new double[fInEEGData.length][NUM_EEG_CH];
        double[][] fOutEEGData = new double[fInEEGData.length][NUM_EEG_CH];

        int dataLength = fInEEGData.length;

        // Artifact Removal
        //ncomp for moving average for windows
        for (int j = 0; j < nComp; j++) { //Loop 14 times..

            for (int k = 0; k < (fInEEGData.length - kCompMat[j]); k++) { //Loop until not enough EEG to mean
                //Getting mean
                //fRefData (k,:) = mean(fInEEGData(k:k+para.kCompMat(j)-1,:));
                for (int ch = 0; ch < NUM_EEG_CH; ch++) {
                    fRefData[k][ch] = getMean(k, (k + kCompMat[j]), ch, fInEEGData);
                }
            }

            int index = kCompMat2[j];
                for (int i=0;i<(dataLength-kCompMat2[j]);i++){
                    for (int ch = 0; ch < NUM_EEG_CH; ch++) {
                        fOutEEGData[i][ch] = fInEEGData[index][ch] - fRefData[i][ch] + fOutEEGData[i][ch];
                    }
                    index++;
                }

        }

        //    fOutEEGData = fOutEEGData/para.nComp;
        for (int row = 0; row < fOutEEGData.length; row++) {
            for (int col = 0; col < NUM_EEG_CH; col++) {
                fOutEEGData[row][col] = fOutEEGData[row][col] / nComp;
            }
        }

        return fOutEEGData;
    }

    public double[][] extractFeatures(double[][] xm) {

        double fs = SAMPLE_RATE;
        double winLen = WINDOW_LENGTH;

        int winSize = (int) Math.floor(winLen * fs); // window size = 2 * 256 = 512

        int winShift = (int) Math.floor(winSize * (100 - OVER_LAP) / 100); //sample overlap 0.5 * 512 = 256

        int numSeg = (int) Math.floor((xm.length - winSize) / winShift) + 1;

        int numChannel = xm[0].length;

        int nband = NUM_BAND;

        double[][] xWinFeature = new double[numSeg][nband * numChannel];
        double[][][] xm_filtered;// = new double[xm.length][xm[0].length][nband]; //Create 3D array last one 6 for 6 different bands

        //bandpass filter them into different bands
        xm_filtered = bandPassFilter(xm);
        double[][][] xWinFeature1 = new double[numSeg][numChannel][nband]; //segment, ch x 4, band x 6

        for (int iSeg = 0; iSeg < numSeg; iSeg++) {

            int xStart = (iSeg) * winShift;// + 1;
            int xEnd = (iSeg) * winShift + winSize;

            for (int iCh = 0; iCh < numChannel; iCh++) {
                for (int band = 0; band < nband; band++) {
                    xWinFeature1[iSeg][iCh][band] = mySum(xm_filtered, xStart, xEnd, iCh, band); //CTG: relative power
                }

                double mySumOfSqueeze = mySqueeze(xWinFeature1, iSeg, iCh);
                for (int band = 0; band < nband; band++) {
                    xWinFeature1[iSeg][iCh][band] = xWinFeature1[iSeg][iCh][band] / mySumOfSqueeze;
                }

            }

            int iFeat = 0;
            for (int j = 0; j < numChannel; j++) {
                for (int m = 0; m < nband; m++) {
                    xWinFeature[iSeg][iFeat] = xWinFeature1[iSeg][j][m];
                    iFeat++;
                }
            }
        }

        return xWinFeature;

    }

    public double[][][] bandPassFilter(double[][] xm) {

        int nband = NUM_BAND;
        double[][][] xm_filtered = new double[xm.length][xm[0].length][nband];

        for (int band = (N_START_BAND - 1); band < nband; band++) {
            for (int i = 0; i < xm_filtered.length; i++) {
                for (int j = 0; j < xm_filtered[i].length; j++) {
                    xm_filtered[i][j][band] = xm[i][j];
                }
            }

            int nSection = BPNumSec[band];
            double[][] fCoe = BPCoe[band];
            double[] fGain = BPGain[band];

            for (int j = 0; j < nSection; j++) {
                double[] B = setAB_for_filter(fCoe[j], 0, 2);
                double[] A = setAB_for_filter(fCoe[j], 3, 5);

                xm_filtered = bpfHelper(fGain[j], B, A, xm_filtered, band);
            }


        }


        return xm_filtered;
    }

    private double getMean(int kStart, int end, int col, double[][] fInEEGData) {
        double sum = 0;
        for (int i = kStart; i < end; i++) {
            sum += fInEEGData[i][col];
        }

        return sum / (double) (end - kStart);
    }

    private double mySqueeze(double[][][] xWinFeature1, int iSeg, int iCh) {
        int sum = 0;

        for (int i = 0; i < NUM_BAND; i++) {
            sum += xWinFeature1[iSeg][iCh][i];
        }
        return sum;
    }

    private double mySum(double[][][] xm_filtered, int xStart, int xEnd, int iCh, int band) {

        double sum = 0;
        for (int i = xStart; i < xEnd; i++) {
            sum += Math.pow(xm_filtered[i][iCh][band], 2);
        }

        return sum;
    }

    private double[][][] bpfHelper(double fGain, double[] B, double[] A, double[][][] xm_filtered, int band) {

        for (int ch = 0; ch < NUM_EEG_CH; ch++) {
            filterIIR(B, A, xm_filtered, ch, band);
        }

        for (int i = 0; i < xm_filtered.length; i++) {
            for (int ch = 0; ch < NUM_EEG_CH; ch++) {
                xm_filtered[i][ch][band] = xm_filtered[i][ch][band] * fGain;
            }
        }
        return xm_filtered;
    }

    private double[] setAB_for_filter(double[] fCoe, int start, int end) {
        double[] result = new double[3];

        int index = 0;
        for (int i = start; i <= end; i++) {
            result[index++] = fCoe[i];
        }

        return result;
    }

    private double[][] deep_copy_2d(double[][] array) {
        double[][] copiedArray = new double[array.length][array[0].length];
        for (int i = 0; i < array.length; i++) {
            System.arraycopy(array[i], 0, copiedArray[i], 0, array[0].length);
        }
        return copiedArray;
    }

}
