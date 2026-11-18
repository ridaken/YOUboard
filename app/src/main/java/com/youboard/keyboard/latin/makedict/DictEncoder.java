/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package com.youboard.keyboard.latin.makedict;

import com.youboard.keyboard.latin.makedict.FusionDictionary.PtNode;
import java.io.IOException;
import java.util.HashMap;

/**
 * An interface of binary dictionary encoder.
 */
public interface DictEncoder {
    void writeDictionary(FusionDictionary dict, FormatSpec.FormatOptions formatOptions)
        throws IOException, UnsupportedFormatException;
    void setPosition(int position);
    int getPosition();
    void writePtNodeCount(int ptNodeCount);
    void writePtNode(PtNode ptNode, FusionDictionary dict, HashMap<Integer, Integer> codePointToOneByteCodeMap);
}
