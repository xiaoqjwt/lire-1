package net.semanticmetadata.lire.indexing;

import com.sun.org.apache.bcel.internal.generic.IfInstruction;
import net.semanticmetadata.lire.DocumentBuilder;
import net.semanticmetadata.lire.imageanalysis.CEDD;
import net.semanticmetadata.lire.imageanalysis.LireFeature;
import net.semanticmetadata.lire.utils.SerializationUtils;
import sun.security.provider.SystemSigner;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/**
 * The Extractor is a configurable class that extracts multiple features from multiple images
 * and puts them into a data file. Main purpose is run multiple extractors at multiple machines
 * and put the data files into one single index. Images are references relatively to the data file,
 * so it should work fine for network file systems.
 * <p/>
 * File format is specified as: (12(345)+)+ with 1-5 being ...
 * <p/>
 * 1. Length of the file name [4 bytes], an int n giving the number of bytes for the file name
 * 2. File name, relative to the outfile [n bytes, see above]
 * 3. Feature index [1 byte], see static members
 * 4. Feature value length [4 bytes], an int k giving the number of bytes encoding the value
 * 5. Feature value [k bytes, see above]
 * <p/>
 * The file is sent through an GZIPOutputStream, so it's compressed in addition.
 *
 * Note that the outfile has to be in a folder parent to all images!
 *
 * @author Mathias Lux, mathias@juggle.at
 *         Date: 08.03.13
 *         Time: 13:15
 */
public class Extractor implements Runnable {
    public static final String[] features = new String[]{
            "net.semanticmetadata.lire.imageanalysis.CEDD",                 // 0
            "net.semanticmetadata.lire.imageanalysis.FCTH",                 // 1
            "net.semanticmetadata.lire.imageanalysis.OpponentHistogram",    // 2
            "net.semanticmetadata.lire.imageanalysis.JointHistogram",       // 3
            "net.semanticmetadata.lire.imageanalysis.AutoColorCorrelogram", // 4
            "net.semanticmetadata.lire.imageanalysis.ColorLayout",          // 5
            "net.semanticmetadata.lire.imageanalysis.EdgeHistogram",        // 6
            "net.semanticmetadata.lire.imageanalysis.Gabor",                // 7
            "net.semanticmetadata.lire.imageanalysis.JCD",                  // 8
            "net.semanticmetadata.lire.imageanalysis.JpegCoefficientHistogram",
            "net.semanticmetadata.lire.imageanalysis.ScalableColor",        // 10
            "net.semanticmetadata.lire.imageanalysis.SimpleColorHistogram", // 11
            "net.semanticmetadata.lire.imageanalysis.Tamura"                // 12
    };

    public static final String[] featureFieldNames = new String[]{
            DocumentBuilder.FIELD_NAME_CEDD,                 // 0
            DocumentBuilder.FIELD_NAME_FCTH,                 // 1
            DocumentBuilder.FIELD_NAME_OPPONENT_HISTOGRAM,   // 2
            DocumentBuilder.FIELD_NAME_JOINT_HISTOGRAM,      // 3
            DocumentBuilder.FIELD_NAME_AUTOCOLORCORRELOGRAM, // 4
            DocumentBuilder.FIELD_NAME_COLORLAYOUT,          // 5
            DocumentBuilder.FIELD_NAME_EDGEHISTOGRAM,        // 6
            DocumentBuilder.FIELD_NAME_GABOR,                // 7
            DocumentBuilder.FIELD_NAME_JCD,                  // 8
            DocumentBuilder.FIELD_NAME_JPEGCOEFFS,
            DocumentBuilder.FIELD_NAME_SCALABLECOLOR,
            DocumentBuilder.FIELD_NAME_COLORHISTOGRAM,
            DocumentBuilder.FIELD_NAME_TAMURA
    };

    static HashMap<String, Integer> feature2index;

    static {
        feature2index = new HashMap<String, Integer>(features.length);
        for (int i = 0; i < features.length; i++) {
            feature2index.put(features[i], i);
        }
    }

    LinkedList<LireFeature> listOfFeatures;
    File fileList = null;
    File outFile = null;

    public Extractor() {
        // default constructor.
        listOfFeatures = new LinkedList<LireFeature>();
    }

    /**
     * Adds a feature to the extractor chain. All those features are extracted from images.
     *
     * @param feature
     */
    public void addFeature(LireFeature feature) {
        listOfFeatures.add(feature);
    }

    /**
     * Sets the file list for processing. One image file per line is fine.
     *
     * @param fileList
     */
    public void setFileList(File fileList) {
        this.fileList = fileList;
    }

    /**
     * Sets the outfile. The outfile has to be in a folder parent to all input images.
     *
     * @param outFile
     */
    public void setOutFile(File outFile) {
        this.outFile = outFile;
    }

    public static void main(String[] args) throws IOException {
        Extractor e = new Extractor();

        // parse programs args ...
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("-i")) {
                // infile ...
                if ((i+1) < args.length)
                    e.setFileList(new File(args[i + 1]));
                else printHelp();
            } else if (arg.startsWith("-o")) {
                // out file
                if ((i+1) < args.length)
                    e.setOutFile(new File(args[i + 1]));
                else printHelp();
            } else if (arg.startsWith("-h")) {
                // help
                printHelp();
            } else if (arg.startsWith("-c")) {
                // config file ...
                Properties p = new Properties();
                p.load(new FileInputStream(new File(args[i + 1])));
                Enumeration<?> enumeration = p.propertyNames();
                while (enumeration.hasMoreElements()) {
                    String key = (String) enumeration.nextElement();
                    if (key.toLowerCase().startsWith("feature.")) {
                        try {
                            e.addFeature((LireFeature) Class.forName(p.getProperty(key)).newInstance());
                        } catch (Exception e1) {
                            System.err.println("Could not add feature named " + p.getProperty(key));
                            e1.printStackTrace();
                        }
                    }
                }
            }
        }
        // check if there is an infile, an outfile and some features to extract.
        if (!e.isConfigured()) {
            printHelp();
        } else {
            e.run();
        }
    }

    private boolean isConfigured() {
        boolean configured = true;
        if (fileList==null || !fileList.exists()) configured = false;
        if (outFile==null) configured = false;
        if (listOfFeatures.size()<1) configured = false;
        return configured;
    }

    private static void printHelp() {
        System.out.println("Help for the Extractor class.\n" +
                "=============================\n" +
                "This help text is shown if you start the Extractor with the '-h' option.\n" +
                "\n" +
                "1. Usage\n" +
                "========\n" +
                "$> Extractor -i <infile> -o <outfile> -c <configfile>\n" +
                "\n" +
                "2. Config File\n" +
                "==============\n" +
                "The config file is a simple java Properties file. It basically gives the \n" +
                "employed features as a list of properties, just like:\n" +
                "\n" +
                "feature.1=net.semanticmetadata.lire.imageanalysis.CEDD\n" +
                "feature.2=net.semanticmetadata.lire.imageanalysis.FCTH\n" +
                "\n" +
                "... and so on. ");
    }


    @Override
    public void run() {
        // check:
        if (fileList == null || !fileList.exists()) {
            System.err.println("No text file with a list of images given.");
            return;
        }
        if (listOfFeatures.size() == 0) {
            System.err.println("No features to extract given.");
            return;
        }

        // do it ...
        byte[] myBuffer = new byte[1024*100];
        int bufferCount = 0;
        try {
            BufferedReader br = new BufferedReader(new FileReader(fileList));
            BufferedOutputStream dos = new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(outFile)));
            String file = null;
            String outFilePath = outFile.getCanonicalPath();
            outFilePath = outFilePath.substring(0, outFilePath.lastIndexOf(outFile.getName()));
            long ms = System.currentTimeMillis();
            int count=0;
            while ((file = br.readLine()) != null) {
                File input = new File(file);
                String relFile = input.getCanonicalPath().substring(outFilePath.length());
                try {
                    BufferedImage img = ImageIO.read(input);
                    byte[] tmpBytes = relFile.getBytes();
                    // everything is written to a buffer and only if no exception is thrown, the image goes to index.
                    System.arraycopy(SerializationUtils.toBytes(tmpBytes.length), 0, myBuffer, 0, 4);
                    bufferCount += 4;
                    // dos.write(SerializationUtils.toBytes(tmpBytes.length));
                    System.arraycopy(tmpBytes, 0, myBuffer, bufferCount, tmpBytes.length);
                    bufferCount += tmpBytes.length;
                    // dos.write(tmpBytes);
                    for (LireFeature feature : listOfFeatures) {
                        feature.extract(img);
                        myBuffer[bufferCount] = (byte) feature2index.get(feature.getClass().getName()).intValue();
                        bufferCount++;
                        // dos.write(feature2index.get(feature.getClass().getName()));
                        tmpBytes = feature.getByteArrayRepresentation();
                        System.arraycopy(SerializationUtils.toBytes(tmpBytes.length), 0, myBuffer, bufferCount, 4);
                        bufferCount += 4;
                        // dos.write(SerializationUtils.toBytes(tmpBytes.length));
                        System.arraycopy(tmpBytes, 0, myBuffer, bufferCount, tmpBytes.length);
                        bufferCount += tmpBytes.length;
                        // dos.write(tmpBytes);
                    }
                    // finally write everything to the stream - in case no exception was thrown..
                    dos.write(myBuffer, 0, bufferCount);
                    dos.write(-1);
                    bufferCount = 0;
                    count++;
                } catch (Exception e) {
                    System.err.println("Error reading image " + relFile + ": " + e.getMessage());
                }
                if (count%100==0)
                    System.out.println(count + " files processed, " + (System.currentTimeMillis()-ms)/count + " ms per file.");
            }
            dos.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
