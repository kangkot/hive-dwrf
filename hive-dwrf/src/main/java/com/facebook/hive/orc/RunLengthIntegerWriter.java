//  Copyright (c) 2013, Facebook, Inc.  All rights reserved.

/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.hive.orc;

import java.io.IOException;

/**
 * A streamFactory that writes a sequence of integers. A control byte is written before
 * each run with positive values 0 to 127 meaning 3 to 130 repetitions, each
 * repetition is offset by a delta. If the control byte is -1 to -128, 1 to 128
 * literal vint values follow.
 */
class RunLengthIntegerWriter extends PositionedOutputStream {
  static final int MAX_DELTA = 127;
  static final int MIN_DELTA = -128;

  private final PositionedOutputStream output;
  private final boolean signed;
  private final long[] literals = new long[RunLengthConstants.MAX_LITERAL_SIZE];
  private int numLiterals = 0;
  private long delta = 0;
  private boolean repeat = false;
  private int tailRunLength = 0;
  private final int numBytes;
  private final boolean useVInts;

  RunLengthIntegerWriter(PositionedOutputStream output,
                         boolean signed, int numBytes, boolean useVInts) {
    this.output = output;
    this.signed = signed;
    this.numBytes = numBytes;
    this.useVInts = useVInts;
  }

  private void writeValues() throws IOException {
    if (numLiterals != 0) {
      if (repeat) {
        output.write(numLiterals - RunLengthConstants.MIN_REPEAT_SIZE);
        output.write((byte) delta);
        SerializationUtils.writeIntegerType(output, literals[0], numBytes, signed, useVInts);
      } else {
        output.write(-numLiterals);
        for(int i=0; i < numLiterals; ++i) {
          SerializationUtils.writeIntegerType(output, literals[i], numBytes, signed, useVInts);
        }
      }
      repeat = false;
      numLiterals = 0;
      tailRunLength = 0;
    }
  }

  @Override
  public void flush() throws IOException {
    writeValues();
    output.flush();
  }

  @Override
  public void write(int value) throws IOException {
    write((long) value);
  }

  void write(long value) throws IOException {
    if (numLiterals == 0) {
      literals[numLiterals++] = value;
      tailRunLength = 1;
    } else if (repeat) {
      if (value == literals[0] + delta * numLiterals) {
        numLiterals += 1;
        if (numLiterals == RunLengthConstants.MAX_REPEAT_SIZE) {
          writeValues();
        }
      } else {
        writeValues();
        literals[numLiterals++] = value;
        tailRunLength = 1;
      }
    } else {
      if (tailRunLength == 1) {
        delta = value - literals[numLiterals - 1];
        if (delta < MIN_DELTA || delta > MAX_DELTA) {
          tailRunLength = 1;
        } else {
          tailRunLength = 2;
        }
      } else if (value == literals[numLiterals - 1] + delta) {
        tailRunLength += 1;
      } else {
        delta = value - literals[numLiterals - 1];
        if (delta < MIN_DELTA || delta > MAX_DELTA) {
          tailRunLength = 1;
        } else {
          tailRunLength = 2;
        }
      }
      if (tailRunLength == RunLengthConstants.MIN_REPEAT_SIZE) {
        if (numLiterals + 1 == RunLengthConstants.MIN_REPEAT_SIZE) {
          repeat = true;
          numLiterals += 1;
        } else {
          numLiterals -= RunLengthConstants.MIN_REPEAT_SIZE - 1;
          long base = literals[numLiterals];
          writeValues();
          literals[0] = base;
          repeat = true;
          numLiterals = RunLengthConstants.MIN_REPEAT_SIZE;
        }
      } else {
        literals[numLiterals++] = value;
        if (numLiterals == RunLengthConstants.MAX_LITERAL_SIZE) {
          writeValues();
        }
      }
    }
  }

  @Override
  void getPosition(PositionRecorder recorder) throws IOException {
    output.getPosition(recorder);
    recorder.addPosition(numLiterals);
  }

  @Override
  long getBufferSize() {
    return output.getBufferSize();
  }

  @Override
  void flush(boolean reuseBuffer) throws IOException {
    writeValues();
    output.flush(reuseBuffer);
  }
}
