/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.deploy.k8s.features

import scala.collection.JavaConverters._

import io.fabric8.kubernetes.api.model.{ContainerPort, ContainerPortBuilder, LocalObjectReferenceBuilder}

import org.apache.spark.{SparkConf, SparkFunSuite}
import org.apache.spark.deploy.k8s.{KubernetesConf, KubernetesDriverSpecificConf, SparkPod}
import org.apache.spark.deploy.k8s.Config._
import org.apache.spark.deploy.k8s.Constants._
import org.apache.spark.deploy.k8s.submit._
import org.apache.spark.internal.config._
import org.apache.spark.ui.SparkUI

class BasicDriverFeatureStepSuite extends SparkFunSuite {

  private val APP_ID = "spark-app-id"
  private val RESOURCE_NAME_PREFIX = "spark"
  private val DRIVER_LABELS = Map("labelkey" -> "labelvalue")
  private val CONTAINER_IMAGE_PULL_POLICY = "IfNotPresent"
  private val APP_NAME = "spark-test"
  private val MAIN_CLASS = "org.apache.spark.examples.SparkPi"
  private val PY_MAIN_CLASS = "org.apache.spark.deploy.PythonRunner"
  private val APP_ARGS = Array("arg1", "arg2", "\"arg 3\"")
  private val CUSTOM_ANNOTATION_KEY = "customAnnotation"
  private val CUSTOM_ANNOTATION_VALUE = "customAnnotationValue"
  private val DRIVER_ANNOTATIONS = Map(CUSTOM_ANNOTATION_KEY -> CUSTOM_ANNOTATION_VALUE)
  private val DRIVER_CUSTOM_ENV1 = "customDriverEnv1"
  private val DRIVER_CUSTOM_ENV2 = "customDriverEnv2"
  private val DRIVER_ENVS = Map(
    DRIVER_CUSTOM_ENV1 -> DRIVER_CUSTOM_ENV1,
    DRIVER_CUSTOM_ENV2 -> DRIVER_CUSTOM_ENV2)
  private val TEST_IMAGE_PULL_SECRETS = Seq("my-secret-1", "my-secret-2")
  private val TEST_IMAGE_PULL_SECRET_OBJECTS =
    TEST_IMAGE_PULL_SECRETS.map { secret =>
      new LocalObjectReferenceBuilder().withName(secret).build()
    }
  private val emptyDriverSpecificConf = KubernetesDriverSpecificConf(
    JavaMainAppResource(None),
    APP_NAME,
    MAIN_CLASS,
    APP_ARGS)

  test("Check the pod respects all configurations from the user.") {
    val sparkConf = new SparkConf()
      .set(KUBERNETES_DRIVER_POD_NAME, "spark-driver-pod")
      .set("spark.driver.cores", "2")
      .set(KUBERNETES_DRIVER_LIMIT_CORES, "4")
      .set(DRIVER_MEMORY.key, "256M")
      .set(DRIVER_MEMORY_OVERHEAD, 200L)
      .set(CONTAINER_IMAGE, "spark-driver:latest")
      .set(IMAGE_PULL_SECRETS, TEST_IMAGE_PULL_SECRETS.mkString(","))
    val kubernetesConf = KubernetesConf(
      sparkConf,
      emptyDriverSpecificConf,
      RESOURCE_NAME_PREFIX,
      APP_ID,
      None,
      DRIVER_LABELS,
      DRIVER_ANNOTATIONS,
      Map.empty,
      Map.empty,
      DRIVER_ENVS,
      Nil,
      hadoopConfSpec = None)

    val featureStep = new BasicDriverFeatureStep(kubernetesConf)
    val basePod = SparkPod.initialPod()
    val configuredPod = featureStep.configurePod(basePod)

    assert(configuredPod.container.getName === DEFAULT_DRIVER_CONTAINER_NAME)
    assert(configuredPod.container.getImage === "spark-driver:latest")
    assert(configuredPod.container.getImagePullPolicy === CONTAINER_IMAGE_PULL_POLICY)

    val expectedPortNames = Set(
      containerPort(DRIVER_PORT_NAME, DEFAULT_DRIVER_PORT),
      containerPort(BLOCK_MANAGER_PORT_NAME, DEFAULT_BLOCKMANAGER_PORT),
      containerPort(UI_PORT_NAME, SparkUI.DEFAULT_PORT)
    )
    val foundPortNames = configuredPod.container.getPorts.asScala.toSet
    assert(expectedPortNames === foundPortNames)

    assert(configuredPod.container.getEnv.size === 3)
    val envs = configuredPod.container
      .getEnv
      .asScala
      .map(env => (env.getName, env.getValue))
      .toMap
    assert(envs(DRIVER_CUSTOM_ENV1) === DRIVER_ENVS(DRIVER_CUSTOM_ENV1))
    assert(envs(DRIVER_CUSTOM_ENV2) === DRIVER_ENVS(DRIVER_CUSTOM_ENV2))

    assert(configuredPod.pod.getSpec().getImagePullSecrets.asScala ===
      TEST_IMAGE_PULL_SECRET_OBJECTS)

    assert(configuredPod.container.getEnv.asScala.exists(envVar =>
      envVar.getName.equals(ENV_DRIVER_BIND_ADDRESS) &&
        envVar.getValueFrom.getFieldRef.getApiVersion.equals("v1") &&
        envVar.getValueFrom.getFieldRef.getFieldPath.equals("status.podIP")))

    val resourceRequirements = configuredPod.container.getResources
    val requests = resourceRequirements.getRequests.asScala
    assert(requests("cpu").getAmount === "2")
    assert(requests("memory").getAmount === "456Mi")
    val limits = resourceRequirements.getLimits.asScala
    assert(limits("memory").getAmount === "456Mi")
    assert(limits("cpu").getAmount === "4")

    val driverPodMetadata = configuredPod.pod.getMetadata
    assert(driverPodMetadata.getName === "spark-driver-pod")
    assert(driverPodMetadata.getLabels.asScala === DRIVER_LABELS)
    assert(driverPodMetadata.getAnnotations.asScala === DRIVER_ANNOTATIONS)
    assert(configuredPod.pod.getSpec.getRestartPolicy === "Never")
    val expectedSparkConf = Map(
      KUBERNETES_DRIVER_POD_NAME.key -> "spark-driver-pod",
      "spark.app.id" -> APP_ID,
      KUBERNETES_EXECUTOR_POD_NAME_PREFIX.key -> RESOURCE_NAME_PREFIX,
      "spark.kubernetes.submitInDriver" -> "true",
      MEMORY_OVERHEAD_FACTOR.key -> MEMORY_OVERHEAD_FACTOR.defaultValue.get.toString)
    assert(featureStep.getAdditionalPodSystemProperties() === expectedSparkConf)
  }

  test("Check appropriate entrypoint rerouting for various bindings") {
    val javaSparkConf = new SparkConf()
      .set(DRIVER_MEMORY.key, "4g")
      .set(CONTAINER_IMAGE, "spark-driver:latest")
    val pythonSparkConf = new SparkConf()
      .set(DRIVER_MEMORY.key, "4g")
      .set(CONTAINER_IMAGE, "spark-driver-py:latest")
    val javaKubernetesConf = KubernetesConf(
      javaSparkConf,
      KubernetesDriverSpecificConf(
        JavaMainAppResource(None),
        APP_NAME,
        PY_MAIN_CLASS,
        APP_ARGS),
      RESOURCE_NAME_PREFIX,
      APP_ID,
      None,
      DRIVER_LABELS,
      DRIVER_ANNOTATIONS,
      Map.empty,
      Map.empty,
      DRIVER_ENVS,
      Nil,
      hadoopConfSpec = None)

    val pythonKubernetesConf = KubernetesConf(
      pythonSparkConf,
      KubernetesDriverSpecificConf(
        PythonMainAppResource(""),
        APP_NAME,
        PY_MAIN_CLASS,
        APP_ARGS),
      RESOURCE_NAME_PREFIX,
      APP_ID,
      None,
      DRIVER_LABELS,
      DRIVER_ANNOTATIONS,
      Map.empty,
      Map.empty,
      DRIVER_ENVS,
      Nil,
      hadoopConfSpec = None)
    val javaFeatureStep = new BasicDriverFeatureStep(javaKubernetesConf)
    val pythonFeatureStep = new BasicDriverFeatureStep(pythonKubernetesConf)
    val basePod = SparkPod.initialPod()
    val configuredJavaPod = javaFeatureStep.configurePod(basePod)
    val configuredPythonPod = pythonFeatureStep.configurePod(basePod)
    assert(configuredJavaPod.container.getImage === "spark-driver:latest")
    assert(configuredPythonPod.container.getImage === "spark-driver-py:latest")
  }

  test("Additional system properties resolve jars and set cluster-mode confs.") {
    val allJars = Seq("local:///opt/spark/jar1.jar", "hdfs:///opt/spark/jar2.jar")
    val allFiles = Seq("https://localhost:9000/file1.txt", "local:///opt/spark/file2.txt")
    val sparkConf = new SparkConf()
      .set(KUBERNETES_DRIVER_POD_NAME, "spark-driver-pod")
      .setJars(allJars)
      .set("spark.files", allFiles.mkString(","))
      .set(CONTAINER_IMAGE, "spark-driver:latest")
    val kubernetesConf = KubernetesConf(
      sparkConf,
      emptyDriverSpecificConf,
      RESOURCE_NAME_PREFIX,
      APP_ID,
      None,
      DRIVER_LABELS,
      DRIVER_ANNOTATIONS,
      Map.empty,
      Map.empty,
      DRIVER_ENVS,
      Nil,
      hadoopConfSpec = None)

    val step = new BasicDriverFeatureStep(kubernetesConf)
    val additionalProperties = step.getAdditionalPodSystemProperties()
    val expectedSparkConf = Map(
      KUBERNETES_DRIVER_POD_NAME.key -> "spark-driver-pod",
      "spark.app.id" -> APP_ID,
      KUBERNETES_EXECUTOR_POD_NAME_PREFIX.key -> RESOURCE_NAME_PREFIX,
      "spark.kubernetes.submitInDriver" -> "true",
      "spark.jars" -> "/opt/spark/jar1.jar,hdfs:///opt/spark/jar2.jar",
      "spark.files" -> "https://localhost:9000/file1.txt,/opt/spark/file2.txt",
      MEMORY_OVERHEAD_FACTOR.key -> MEMORY_OVERHEAD_FACTOR.defaultValue.get.toString)
    assert(additionalProperties === expectedSparkConf)
  }

  // Memory overhead tests. Tuples are:
  //   test name, main resource, overhead factor, expected factor
  Seq(
    ("java", JavaMainAppResource(None), None, MEMORY_OVERHEAD_FACTOR.defaultValue.get),
    ("python default", PythonMainAppResource(null), None, NON_JVM_MEMORY_OVERHEAD_FACTOR),
    ("python w/ override", PythonMainAppResource(null), Some(0.9d), 0.9d),
    ("r default", RMainAppResource(null), None, NON_JVM_MEMORY_OVERHEAD_FACTOR)
  ).foreach { case (name, resource, factor, expectedFactor) =>
    test(s"memory overhead factor: $name") {
      // Choose a driver memory where the default memory overhead is > MEMORY_OVERHEAD_MIN_MIB
      val driverMem = MEMORY_OVERHEAD_MIN_MIB / MEMORY_OVERHEAD_FACTOR.defaultValue.get * 2

      // main app resource, overhead factor
      val sparkConf = new SparkConf(false)
        .set(CONTAINER_IMAGE, "spark-driver:latest")
        .set(DRIVER_MEMORY.key, s"${driverMem.toInt}m")
      factor.foreach { value => sparkConf.set(MEMORY_OVERHEAD_FACTOR, value) }
      val driverConf = emptyDriverSpecificConf.copy(mainAppResource = resource)
      val conf = KubernetesConf(
        sparkConf,
        driverConf,
        RESOURCE_NAME_PREFIX,
        APP_ID,
        None,
        DRIVER_LABELS,
        DRIVER_ANNOTATIONS,
        Map.empty,
        Map.empty,
        DRIVER_ENVS,
        Nil,
        hadoopConfSpec = None)
      val step = new BasicDriverFeatureStep(conf)
      val pod = step.configurePod(SparkPod.initialPod())
      val mem = pod.container.getResources.getRequests.get("memory").getAmount()
      val expected = (driverMem + driverMem * expectedFactor).toInt
      assert(mem === s"${expected}Mi")

      val systemProperties = step.getAdditionalPodSystemProperties()
      assert(systemProperties(MEMORY_OVERHEAD_FACTOR.key) === expectedFactor.toString)
    }
  }

  def containerPort(name: String, portNumber: Int): ContainerPort =
    new ContainerPortBuilder()
      .withName(name)
      .withContainerPort(portNumber)
      .withProtocol("TCP")
      .build()
}
