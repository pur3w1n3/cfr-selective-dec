package com.aq.cfrselect.core;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/** Minimal class-file reader for top-level/inner-class relationships. */
final class ClassFileMetadata {
    private static final int CLASS_MAGIC = 0xCAFEBABE;

    final String outerEntryName;

    private ClassFileMetadata(String outerEntryName) {
        this.outerEntryName = outerEntryName;
    }

    static ClassFileMetadata read(InputStream input, String source) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(input))) {
            if (in.readInt() != CLASS_MAGIC) {
                throw new IOException("Invalid class file: " + source);
            }
            in.readUnsignedShort();
            in.readUnsignedShort();

            Object[] constantPool = readConstantPool(in, source);
            in.readUnsignedShort();
            int thisClassIndex = in.readUnsignedShort();
            in.readUnsignedShort();

            int interfaceCount = in.readUnsignedShort();
            skipFully(in, interfaceCount * 2L);
            skipMembers(in, source);
            skipMembers(in, source);

            String innerOuter = null;
            String enclosingOuter = null;
            int attributeCount = in.readUnsignedShort();
            for (int i = 0; i < attributeCount; i++) {
                String attributeName = utf8(constantPool, in.readUnsignedShort(), source);
                long attributeLength = Integer.toUnsignedLong(in.readInt());
                if ("InnerClasses".equals(attributeName)) {
                    long consumed = 2L;
                    int classCount = in.readUnsignedShort();
                    long expectedLength = 2L + classCount * 8L;
                    if (expectedLength > attributeLength) {
                        throw new IOException("Invalid InnerClasses attribute in " + source);
                    }
                    for (int j = 0; j < classCount; j++) {
                        int innerClassIndex = in.readUnsignedShort();
                        int outerClassIndex = in.readUnsignedShort();
                        in.readUnsignedShort();
                        in.readUnsignedShort();
                        consumed += 8L;
                        if (innerClassIndex == thisClassIndex && outerClassIndex != 0) {
                            innerOuter = classEntry(constantPool, outerClassIndex, source);
                        }
                    }
                    skipRemaining(in, attributeLength, consumed, source);
                } else if ("EnclosingMethod".equals(attributeName)) {
                    if (attributeLength < 4L) {
                        throw new IOException("Invalid EnclosingMethod attribute in " + source);
                    }
                    int enclosingClassIndex = in.readUnsignedShort();
                    in.readUnsignedShort();
                    enclosingOuter = classEntry(constantPool, enclosingClassIndex, source);
                    skipRemaining(in, attributeLength, 4L, source);
                } else {
                    skipFully(in, attributeLength);
                }
            }
            return new ClassFileMetadata(innerOuter != null ? innerOuter : enclosingOuter);
        }
    }

    private static Object[] readConstantPool(DataInputStream in, String source) throws IOException {
        Object[] constantPool = new Object[in.readUnsignedShort()];
        for (int i = 1; i < constantPool.length; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1:
                    constantPool[i] = in.readUTF();
                    break;
                case 3:
                case 4:
                    in.readInt();
                    break;
                case 5:
                case 6:
                    in.readLong();
                    i++;
                    break;
                case 7:
                    constantPool[i] = Integer.valueOf(in.readUnsignedShort());
                    break;
                case 8:
                case 16:
                case 19:
                case 20:
                    in.readUnsignedShort();
                    break;
                case 9:
                case 10:
                case 11:
                case 12:
                case 17:
                case 18:
                    in.readUnsignedShort();
                    in.readUnsignedShort();
                    break;
                case 15:
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                    break;
                default:
                    throw new IOException("Unsupported constant pool tag " + tag + " in " + source);
            }
        }
        return constantPool;
    }

    private static void skipMembers(DataInputStream in, String source) throws IOException {
        int memberCount = in.readUnsignedShort();
        for (int i = 0; i < memberCount; i++) {
            in.readUnsignedShort();
            in.readUnsignedShort();
            in.readUnsignedShort();
            int attributeCount = in.readUnsignedShort();
            for (int j = 0; j < attributeCount; j++) {
                in.readUnsignedShort();
                long length = Integer.toUnsignedLong(in.readInt());
                skipFully(in, length);
            }
        }
    }

    private static String classEntry(Object[] constantPool, int classIndex, String source)
            throws IOException {
        if (classIndex <= 0 || classIndex >= constantPool.length
                || !(constantPool[classIndex] instanceof Integer)) {
            throw new IOException("Invalid class reference in " + source);
        }
        int nameIndex = ((Integer) constantPool[classIndex]).intValue();
        return utf8(constantPool, nameIndex, source) + ".class";
    }

    private static String utf8(Object[] constantPool, int index, String source) throws IOException {
        if (index <= 0 || index >= constantPool.length
                || !(constantPool[index] instanceof String)) {
            throw new IOException("Invalid UTF-8 constant in " + source);
        }
        return (String) constantPool[index];
    }

    private static void skipRemaining(DataInputStream in, long total, long consumed, String source)
            throws IOException {
        if (consumed > total) {
            throw new IOException("Invalid attribute length in " + source);
        }
        skipFully(in, total - consumed);
    }

    private static void skipFully(DataInputStream in, long count) throws IOException {
        long remaining = count;
        while (remaining > 0L) {
            long skipped = in.skip(remaining);
            if (skipped > 0L) {
                remaining -= skipped;
                continue;
            }
            if (in.read() < 0) {
                throw new EOFException("Unexpected end of class file");
            }
            remaining--;
        }
    }
}
