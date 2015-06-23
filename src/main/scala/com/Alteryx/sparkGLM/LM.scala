package com.Alteryx.sparkGLM

import breeze.linalg._
import breeze.numerics._
import edu.berkeley.cs.amplab.mlmatrix
import edu.berkeley.cs.amplab.mlmatrix._
import edu.berkeley.cs.amplab.mlmatrix.{NormalEquations, RowPartitionedMatrix}
import edu.berkeley.cs.amplab.mlmatrix.util.Utils
import edu.berkeley.cs.amplab.mlmatrix.NormalEquations._

import org.apache.spark.{SparkContext, SparkException}
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import org.apache.spark.util.Utils
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.types._
import org.apache.spark.sql.functions

case class PreLM(coefs: DenseMatrix[Double],
                 xtxi: DenseMatrix[Double],
                 sse: Double,
                 r2: Double,
                 fStat: Double)

case class LM(xnames: Array[String],
              yname: String,
              coefs: DenseMatrix[Double],
              stdErr: Array[Double],
              sigma: Double,
              r2: Double,
              fStat: Double,
              nrow: Double)

object LM {

  // Get X'X and X'y when the DataFrames are row partitioned
  private def rowPartitionedComponents(
      X: RowPartitionedMatrix,
      Y: RowPartitionedMatrix): (DenseMatrix[Double], DenseMatrix[Double])  = {
    val XY = X.rdd.zip(Y.rdd).map(x => (x._1.mat, x._2.mat))
    val ATA_ATb = XY.map { part =>
      (part._1.t * part._1, part._1.t * part._2)
      }

      val treeBranchingFactor = X.rdd.context.getConf.getInt("spark.mlmatrix.treeBranchingFactor", 2).toInt
      val depth = math.ceil(math.log(ATA_ATb.partitions.size)/math.log(treeBranchingFactor)).toInt
      val reduced = edu.berkeley.cs.amplab.mlmatrix.util.Utils.treeReduce(ATA_ATb, utils.reduceNormal, depth=depth)

      reduced
  }


// Figure out how to get (pred - yMean)'(pred - yMean) and (y - yMean)'(y - yMean)
  // Calculate the sum of the squared errors for the case of partitioned data
  private def rowPartitionedSSE(
          X: RowPartitionedMatrix,
          Y: RowPartitionedMatrix,
          coefs: DenseMatrix[Double],
          nrows: Double,
          ncols: Double): (Double, Double, Double) = {
    val npart = X.rdd.partitions.size
    val ySums = Y.rdd.map(y => sum(y.mat(::, 0)))
    val yMean = (ySums.collect.reduce(_+_))/nrows
    val XY = X.rdd.zip(Y.rdd).map(x => (x._1.mat, x._2.mat))
    // Create an array of 3-tuples, where each element is a double. One
    // tuple per partition
    val errTopBot1 = XY.map(x => (
      ((x._2 - (x._1 * coefs)).t * (x._2 - (x._1 * coefs))).toArray(0),
      (((x._1 * coefs) :+ (-1.0*yMean)).t * ((x._1 * coefs) :+ (-1.0*yMean))).toArray(0),
      ((x._2 :+ (-1.0*yMean)).t * (x._2 :+ (-1.0*yMean))).toArray(0)))
    val errTopBot = errTopBot1.collect
    var sse = errTopBot(0)._1
    var top = errTopBot(0)._2
    var bot = errTopBot(0)._3
    for(i <- 1 to (npart - 1)) {
      sse = sse + errTopBot(i)._1
      top = top + errTopBot(i)._2
      bot = bot + errTopBot(i)._3
    }
    val r2 = top/bot
    val fStat = ((bot - sse)/(ncols - 1.0))/(sse/(nrows - ncols))
    (sse, r2, fStat)
  }

  // Get OLS coefs and other items in the case of a single partition DataFrame
  private def fitSingle(
      x: DataFrame,
      y: DataFrame): PreLM = {
    val nrows = y.count.toDouble
    val xm = utils.dfToDenseMatrix(x)
    val ym = utils.dfToDenseMatrix(y)
    val XtXi = inv(xm.t * xm)
    val XtY = xm.t * ym
    val coefs = XtXi * XtY
    val pred = xm * coefs
    val err = ym - pred
    val sse = (err.t * err).toArray(0)
    val yMean = sum(ym(::, 0))/y.count.toDouble
    val top = ((pred :+ (-1.0*yMean)).t * (pred :+ (-1.0*yMean))).toArray(0)
    val bot = ((ym :+ (-1.0*yMean)).t * (ym :+ (-1.0*yMean))).toArray(0)
    val r2 = top/bot
    val fStat = ((bot - sse)/(x.columns.size.toDouble - 1.0))/(sse/(nrows - x.columns.size.toDouble))

    new PreLM(coefs = coefs,
        xtxi = XtXi,
        sse = sse,
        r2 = r2,
        fStat = fStat)
  }

  // Get OLS coefs and other items in the case of a multiple partition DataFrame
  private def fitMultiple(
      x: DataFrame,
      y: DataFrame): PreLM = {
    val xm = RowPartitionedMatrix.fromMatrix(utils.dataFrameToMatrix(x))
    val ym = RowPartitionedMatrix.fromMatrix(utils.dataFrameToMatrix(y))
    //val xm = RowPartitionedMatrix.fromDataFrame(x)
    //val ym = RowPartitionedMatrix.fromDataFrame(y)
    val components = rowPartitionedComponents(xm, ym)
    val XtXi = inv(components._1)
    val XtY = components._2
    val coefs = XtXi * XtY
    val nrows = y.count.toDouble
    val ncols = x.columns.size.toDouble
    val sseR2 = rowPartitionedSSE(xm, ym, coefs, nrows, ncols)

    new PreLM(coefs = coefs,
        xtxi = XtXi,
        sse = sseR2._1,
        r2 = sseR2._2,
        fStat = sseR2._3)
  }

  // The main method for fitting an linear regression model. The method calls
  // either fitSingle or fitMultiple depending on whether the
  def fit(x: DataFrame,
          y: DataFrame): LM = {
    require(x.dtypes.forall(_._2 == "DoubleType"),
      "The provided DataFrame must contain all 'DoubleType' columns")
    require(x.rdd.partitions.size == y.rdd.partitions.size,
      "The two DataFrames must have the same number of paritions")
    require(x.count == y.count,
      "The two DataFrames must have the same number of rows")
    require(y.columns.size == 1,
      "The 'y' DataFrame must have only one column")
    val yvar = y.columns
    val nrow = y.count.toDouble
    val single = x.rdd.partitions.size == 1
    val components = if (single) fitSingle(x, y) else fitMultiple(x,y)
    val coefs = components.coefs
    val XtXi = components.xtxi
    val sse = components.sse
    val r2 = components.r2
    val fStat = components.fStat
    val sig2 = sse/(nrow - coefs.rows)
    val sigma = scala.math.sqrt(sig2)
    val seCoef = sig2 :* diag(XtXi)
    sqrt.inPlace(seCoef)

    new LM(xnames = x.columns,
        yname = y.columns(0),
        coefs = coefs,
        stdErr = seCoef.toArray,
        sigma = sigma,
        r2 = r2,
        fStat = fStat,
        nrow = nrow)
  }

  // A predict method for LM objects
  // TODO: it needs error checking, and it will need to be able to address
  // DataFrames with more than one partition
  case class predicted(index: Int, value: Double)

  def predict(obj: LM, newData: DataFrame): DataFrame = {
    val newX = utils.dfToDenseMatrix(newData)
    val predVals = newX * obj.coefs //This is a DenseMatrix[Double]
    //Create an RDD[predicted(index, value)]
    val predRDD = newData.sqlContext.sparkContext.parallelize(
      predVals.toArray.zipWithIndex.map { elem =>
        predicted(elem._2, elem._1)
      }
    )
    //Create a DataFrame with schema inferred from `predicted` case class
    newData.sqlContext.createDataFrame(predRDD)
  }

  // TODO: Create a summary method for prining model output.
  // def summary(obj: LM): LMSummary = {
  //
  //
  // }

}