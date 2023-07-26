/*
 * Copyright (c) 2023, NVIDIA CORPORATION.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nvidia.spark.rapids

import java.io.File

import org.apache.hadoop.fs.FileUtil.fullyDelete

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.rapids.{MyDenseVector, MyDenseVectorUDT}
import org.apache.spark.sql.types._

class OrcQuerySuite extends SparkQueryCompareTestSuite {

  private def getSchema: StructType = new StructType(Array(
    StructField("c0", DataTypes.IntegerType),
    StructField("c1", new MyDenseVectorUDT)
  ))

  private def getData: Seq[Row] = Seq(Row(1, new MyDenseVector(Array(0.25, 2.25, 4.25))))

  private def getDf(spark: SparkSession): DataFrame = {
    spark.createDataFrame(
      SparkContext.getOrCreate().parallelize(getData, numSlices = 1),
      getSchema)
  }

  Seq("orc", "").foreach { v1List =>
    val sparkConf = new SparkConf().set("spark.sql.sources.useV1SourceList", v1List)
    testGpuWriteFallback(
      "Writing User Defined Type(UDT) to ORC fall back, source list is (" + v1List + ")",
      "DataWritingCommandExec",
      spark => getDf(spark),
      // WriteFilesExec is a new operator from Spark version 340, for simplicity, add it here for
      // all Spark versions.
      execsAllowedNonGpu = Seq("DataWritingCommandExec", "WriteFilesExec", "ShuffleExchangeExec"),
      conf = sparkConf
    ) { frame =>
      val tempFile = File.createTempFile("orc-test-udt-write", ".orc")
      try {
        frame.write.mode("overwrite").orc(tempFile.getAbsolutePath)
      } finally {
        fullyDelete(tempFile)
      }
    }
  }

  /**
   * udt.orc is generated by CPU, the schema and the data are from `getSchema` and `getData`.
   * udt.orc meta is: struct<c0:int,c1:array<double>>,
   * The MyDenseVectorUDT type is converted to array<double> in the ORC file.
   * Gpu can read this ORC file when not specifying the schema
   */
  testSparkResultsAreEqual("Reading User Defined Type(UDT) from ORC, not specify schema",
    spark => {
      val path = TestResourceFinder.getResourcePath("udt.orc")
      // not specify schema
      spark.read.orc(path)
    }
  ) {
    frame => frame
  }

  /**
   * udt.orc is generated by CPU, the schema and the data are from `getSchema` and `getData`.
   * udt.orc meta is: struct<c0:int,c1:array<double>>,
   * The MyDenseVectorUDT type is converted to array<double> in the ORC file.
   */
  testGpuFallback("Reading User Defined Type(UDT) from ORC falls back when specify schema",
    "FileSourceScanExec",
    spark => {
      val path = TestResourceFinder.getResourcePath("udt.orc")
      // specify schema
      spark.read.schema(getSchema).orc(path)
    },
    execsAllowedNonGpu = Seq("FileSourceScanExec", "ShuffleExchangeExec")
  ) {
    frame => frame
  }
}