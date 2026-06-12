package com.aq.cfrselect.core;

import java.io.IOException;
import java.io.InputStream;

interface InputSource {
    InputStream open() throws IOException;

    default InputSource sibling(String siblingEntryName) {
        return null;
    }
}
