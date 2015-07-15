package trainer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import util.FileIO;
import autodiff.Graph;
import datastructs.DataSequence;
import datastructs.DataSet;
import datastructs.DataStep;
import loss.Loss;
import matrix.Matrix;
import model.Model;

public class Trainer {
	
	public static double decayRate = 0.999;
	public static double smoothEpsilon = 1e-8;
	public static double gradientClipValue = 5;
	public static double regularization = 0.000001; // L2 regularization strength
	
	public static void train(int trainingEpochs, double learningRate, Model model, DataSet data, int reportEveryNthEpoch, boolean initFromSaved, boolean overwriteSaved, String savePath, Random rng) throws Exception {
		System.out.println("--------------------------------------------------------------");
		if (initFromSaved) {
			System.out.println("initializing model from saved state...");
			try {
				model = (Model)FileIO.deserialize(savePath);
				data.DisplayReport(model, rng);
			}
			catch (Exception e) {
				System.out.println("Oops. Unable to load from a saved state.");
				System.out.println("WARNING: " + e.getMessage());
				System.out.println("Continuing from freshly initialized model instead.");
			}
		}
		for (int epoch = 0; epoch < trainingEpochs; epoch++) {
			System.out.println("epoch["+(epoch+1)+"/"+trainingEpochs+"]");
			
			double reportedLossTrain = pass(learningRate, model, data.training, true, data.lossTraining, data.lossReporting);
			if (Double.isNaN(reportedLossTrain) || Double.isInfinite(reportedLossTrain)) {
				throw new Exception("WARNING: invalid value for training loss. Try lowering learning rate.");
			}
			double reportedLossValidation = 0;
			double reportedLossTesting = 0;
			if (data.validation != null) {
				reportedLossValidation = pass(learningRate, model, data.validation, false, data.lossTraining, data.lossReporting);
			}
			if (data.testing != null) {
				reportedLossTesting = pass(learningRate, model, data.testing, false, data.lossTraining, data.lossReporting);
			}
			String show = "\ttrain loss = "+String.format("%.5f", reportedLossTrain);
			if (data.validation != null) {
				show += "\n\tvalid loss = "+String.format("%.5f", reportedLossValidation);
			}
			if (data.testing != null) {
				show += "\n\ttest loss  = "+String.format("%.5f", reportedLossTesting);
			}
			System.out.println(show);
			
			if (epoch % reportEveryNthEpoch == reportEveryNthEpoch - 1) {
				data.DisplayReport(model, rng);
			}
			
			if (overwriteSaved) {
				FileIO.serialize(savePath, model);
			}
			
			if (reportedLossTrain == 0 && reportedLossValidation == 0) {
				System.out.println("--------------------------------------------------------------");
				System.out.println("\nDONE.");
				break;
			}
		}
	}
	
	public static double pass(double learningRate, Model model, List<DataSequence> sequences, boolean applyTraining, Loss lossTraining, Loss lossReporting) throws Exception {
		
		double numerLoss = 0;
		double denomLoss = 0;
		
		for (DataSequence seq : sequences) {
			model.resetState();
			Graph g = new Graph(applyTraining);
			for (DataStep step : seq.steps) {
				Matrix output = model.forward(step.input, g);				
				if (step.targetOutput != null) {
					double loss = lossReporting.measure(output, step.targetOutput);
					if (Double.isNaN(loss) || Double.isInfinite(loss)) {
						return loss;
					}
					numerLoss += loss;
					denomLoss++;			
					if (applyTraining) {
						lossTraining.backward(output, step.targetOutput);
					}
				}
			}
			List<DataSequence> thisSequence = new ArrayList<>();
			thisSequence.add(seq);
			if (applyTraining) {
				g.backward(); //backprop dw values
				updateModelParams(model, learningRate); //update params
			}	
		}
		return numerLoss/denomLoss;
	}
	
	public static void updateModelParams(Model model, double stepSize) throws Exception {
		for (Matrix m : model.getParameters()) {
			for (int i = 0; i < m.w.length; i++) {
				
				// rmsprop adaptive learning rate
				double mdwi = m.dw[i];
				m.stepCache[i] = m.stepCache[i] * decayRate + (1 - decayRate) * mdwi * mdwi;
				
				// gradient clip
				if (mdwi > gradientClipValue) {
					mdwi = gradientClipValue;
				}
				if (mdwi < -gradientClipValue) {
					mdwi = -gradientClipValue;
				}
				
				// update (and regularize)
				m.w[i] += - stepSize * mdwi / Math.sqrt(m.stepCache[i] + smoothEpsilon) - regularization * m.w[i];
				m.dw[i] = 0;
			}
		}
	}
}
