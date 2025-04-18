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

package org.apache.flink.streaming.api.operators;

import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.functions.ReduceFunction;
import org.apache.flink.api.common.functions.RichReduceFunction;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.base.IntSerializer;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.TestHarnessUtil;
import org.apache.flink.streaming.util.asyncprocessing.AsyncKeyedOneInputStreamOperatorTestHarness;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link StreamGroupedReduceAsyncStateOperator}. These test that:
 *
 * <ul>
 *   <li>RichFunction methods are called correctly
 *   <li>Timestamps of processed elements match the input timestamp
 *   <li>Watermarks are correctly forwarded
 * </ul>
 */
class StreamGroupedReduceAsyncStateOperatorTest {

    @Test
    void testGroupedReduce() throws Exception {

        KeySelector<Integer, Integer> keySelector = new IntegerKeySelector();

        StreamGroupedReduceAsyncStateOperator<Integer> operator =
                new StreamGroupedReduceAsyncStateOperator<>(
                        new MyReducer(), IntSerializer.INSTANCE);

        try (AsyncKeyedOneInputStreamOperatorTestHarness<Integer, Integer, Integer> testHarness =
                AsyncKeyedOneInputStreamOperatorTestHarness.create(
                        operator, keySelector, BasicTypeInfo.INT_TYPE_INFO)) {

            long initialTime = 0L;
            ConcurrentLinkedQueue<Object> expectedOutput = new ConcurrentLinkedQueue<>();

            testHarness.open();

            testHarness.processElement(new StreamRecord<>(1, initialTime + 1));
            testHarness.processElement(new StreamRecord<>(1, initialTime + 2));
            testHarness.processWatermark(new Watermark(initialTime + 2));
            testHarness.processElement(new StreamRecord<>(2, initialTime + 3));
            testHarness.processElement(new StreamRecord<>(2, initialTime + 4));
            testHarness.processElement(new StreamRecord<>(3, initialTime + 5));

            expectedOutput.add(new StreamRecord<>(1, initialTime + 1));
            expectedOutput.add(new StreamRecord<>(2, initialTime + 2));
            expectedOutput.add(new Watermark(initialTime + 2));
            expectedOutput.add(new StreamRecord<>(2, initialTime + 3));
            expectedOutput.add(new StreamRecord<>(4, initialTime + 4));
            expectedOutput.add(new StreamRecord<>(3, initialTime + 5));

            TestHarnessUtil.assertOutputEquals(
                    "Output was not correct.", expectedOutput, testHarness.getOutput());
        }
    }

    @Test
    void testOpenClose() throws Exception {

        KeySelector<Integer, Integer> keySelector = new IntegerKeySelector();

        StreamGroupedReduceAsyncStateOperator<Integer> operator =
                new StreamGroupedReduceAsyncStateOperator<>(
                        new TestOpenCloseReduceFunction(), IntSerializer.INSTANCE);
        AsyncKeyedOneInputStreamOperatorTestHarness<Integer, Integer, Integer> testHarness =
                AsyncKeyedOneInputStreamOperatorTestHarness.create(
                        operator, keySelector, BasicTypeInfo.INT_TYPE_INFO);

        long initialTime = 0L;

        testHarness.open();

        testHarness.processElement(new StreamRecord<>(1, initialTime));
        testHarness.processElement(new StreamRecord<>(2, initialTime));

        testHarness.close();

        assertThat(TestOpenCloseReduceFunction.openCalled)
                .as("RichFunction methods where not called.")
                .isTrue();
        assertThat(testHarness.getOutput()).as("Output contains no elements.").isNotEmpty();
    }

    // This must only be used in one test, otherwise the static fields will be changed
    // by several tests concurrently
    private static class TestOpenCloseReduceFunction extends RichReduceFunction<Integer> {
        private static final long serialVersionUID = 1L;

        public static boolean openCalled = false;
        public static boolean closeCalled = false;

        @Override
        public void open(OpenContext openContext) throws Exception {
            super.open(openContext);
            assertThat(closeCalled).as("Close called before open.").isFalse();
            openCalled = true;
        }

        @Override
        public void close() throws Exception {
            super.close();
            assertThat(openCalled).as("Open was not called before close.").isTrue();
            closeCalled = true;
        }

        @Override
        public Integer reduce(Integer in1, Integer in2) throws Exception {
            assertThat(openCalled).as("Open was not called before run.").isTrue();
            return in1 + in2;
        }
    }

    // Utilities

    private static class MyReducer implements ReduceFunction<Integer> {

        private static final long serialVersionUID = 1L;

        @Override
        public Integer reduce(Integer value1, Integer value2) throws Exception {
            return value1 + value2;
        }
    }

    private static class IntegerKeySelector implements KeySelector<Integer, Integer> {
        private static final long serialVersionUID = 1L;

        @Override
        public Integer getKey(Integer value) throws Exception {
            return value;
        }
    }

    private static TypeInformation<Integer> typeInfo = BasicTypeInfo.INT_TYPE_INFO;
}
