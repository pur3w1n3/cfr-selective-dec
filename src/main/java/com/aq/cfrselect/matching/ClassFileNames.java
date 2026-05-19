package com.aq.cfrselect.matching;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class ClassFileNames {
    private static final int CLASS_MAGIC = 0xCAFEBABE;

    private ClassFileNames() {
    }

    public static String readClassEntryName(Path classFile) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(classFile)))) {
            if (in.readInt() != CLASS_MAGIC) {
                throw new IOException("Invalid class file: " + classFile);
            }

            in.readUnsignedShort();
            in.readUnsignedShort();

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
                        throw new IOException("Unsupported constant pool tag " + tag + " in " + classFile);
                }
            }

            in.readUnsignedShort();
            int thisClassIndex = in.readUnsignedShort();
            if (thisClassIndex <= 0 || thisClassIndex >= constantPool.length) {
                throw new IOException("Invalid class reference in " + classFile);
            }

            Object classEntry = constantPool[thisClassIndex];
            if (!(classEntry instanceof Integer)) {
                throw new IOException("Invalid class name entry in " + classFile);
            }

            int nameIndex = ((Integer) classEntry).intValue();
            if (nameIndex <= 0 || nameIndex >= constantPool.length) {
                throw new IOException("Invalid class name index in " + classFile);
            }

            Object className = constantPool[nameIndex];
            if (!(className instanceof String)) {
                throw new IOException("Missing class name in " + classFile);
            }
            return className + ".class";
        }
    }
}
