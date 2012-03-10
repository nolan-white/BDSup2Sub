/*
 * Copyright 2012 Volker Oth (0xdeadbeef) / Miklos Juhasz (mjuhasz)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package bdsup2sub.core;

import bdsup2sub.bitmap.Bitmap;
import bdsup2sub.bitmap.BitmapWithPalette;
import bdsup2sub.bitmap.ErasePatch;
import bdsup2sub.bitmap.Palette;
import bdsup2sub.gui.MainFrameView;
import bdsup2sub.gui.Progress;
import bdsup2sub.supstream.*;
import bdsup2sub.tools.EnhancedPngEncoder;
import bdsup2sub.tools.Props;
import bdsup2sub.utils.FilenameUtils;
import bdsup2sub.utils.ToolBox;
import com.mortennobel.imagescaling.ResampleFilter;

import javax.swing.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;

import static bdsup2sub.core.Constants.*;
import static bdsup2sub.utils.TimeUtils.ptsToTimeStr;
import static com.mortennobel.imagescaling.ResampleFilters.*;

/**
 * This class contains the core functionality of BDSup2Sub.<br>
 * It's meant to be used from the command line as well as from the GUI.
 */
public class Core  extends Thread {

    private static final String INI_FILE = "bdsup2sup.ini";

    /** Enumeration of functionalities executed in the started thread */
    private enum RunType {
        /** read a SUP stream */
        READSUP,
        /** read a SUP stream */
        READXML,
        /** read a VobSub stream */
        READVOBSUB,
        /** read a SUP/IFO stream */
        READSUPIFO,
        /** write a VobSub stream */
        CREATESUB,
        /** write a BD-SUP stream */
        CREATESUP,
        /** move all captions */
        MOVEALL
    }

    /** Enumeration of caption types (used for moving captions) */
    private enum CaptionType {
        /** caption in upper half of the screen */
        UP,
        /** caption in lower half of the screen */
        DOWN,
        /** caption covering more or less the whole screen */
        FULL
    }

    private static final int RECENT_FILE_COUNT = 5;

    /** Current DVD palette (for create mode) - initialized as default */
    private static Palette currentDVDPalette = new Palette(
            DEFAULT_PALETTE_RED, DEFAULT_PALETTE_GREEN, DEFAULT_PALETTE_BLUE, DEFAULT_PALETTE_ALPHA, true
    );

    private static final boolean CONVERT_RESOLUTION_BY_DEFAULT = false;
    private static final Resolution DEFAULT_TARGET_RESOLUTION = Resolution.PAL;
    private static final boolean CONVERTS_FRAMERATE_BY_DEFAULT = false;
    private static final double  DEFAULT_SOURCE_FRAMERATE = Framerate.FPS_23_976.getValue();
    private static final double  DEFAULT_TARGET_FRAMERATE = Framerate.PAL.getValue();
    private static final int     DEFAULT_PTS_DELAY = 0;
    private static final boolean FIX_SHORT_FRAMES_BY_DEFAULT = false;
    private static final int     DEAFULT_MIN_DISPLAY_TIME_PTS = 90*500;
    private static final boolean APPLY_FREE_SCALE_BY_DEFAULT = false;
    private static final int MIN_IMAGE_DIMENSION = 8;
    private static final double  DEAFULT_FREE_SCALE_X = 1.0;
    private static final double  DEFAULT_FREE_SCALE_Y = 1.0;
    public static final double MIN_FREE_SCALE_FACTOR = 0.5;
    public static final double MAX_FREE_SCALE_FACTOR = 2.0;

    private static boolean convertResolutionSet;
    private static boolean resolutionTrgSet;
    private static boolean convertFpsSet;
    private static boolean fpsTrgSet;
    private static boolean delayPtsSet;
    private static boolean fixShortFramesSet;
    private static boolean minTimePtsSet;
    private static boolean applyFreeScaleSet;

    /** Palette imported from SUB/IDX or SUP/IFO */
    private static Palette defaultSourceDVDPalette;
    /** Current palette based on the one imported from SUB/IDX or SUP/IFO */
    private static Palette currentSourceDVDPalette;
    /** Default alpha map */
    private static final int DEFAULT_ALPHA[] = { 0, 0xf, 0xf, 0xf};

    /** Class used to load/store properties persistently */
    public static Props  props;
    /** Full name of the INI file to load and store properties (including path) */
    private static String fnameProps;

    /** Index of language to be used for SUB/IDX export (also used for XML export) */
    private static int languageIdx = 0;
    /** Converted unpatched target bitmap of current subpicture - just for display */
    private static Bitmap trgBitmapUnpatched;
    /** Converted target bitmap of current subpicture - just for display */
    private static Bitmap trgBitmap;
    /** Palette of target caption */
    private static Palette trgPal;
    /** Used for creating VobSub streams */
    private static SubPictureDVD subVobTrg;

    /** Used for handling BD SUPs */
    private static SupBD supBD;
    /** Used for handling HD-DVD SUPs */
    private static SupHD supHD;
    /** Used for handling Xmls */
    private static SupXml supXml;
    /** Used for handling VobSub */
    private static SubDVD subDVD;
    /** Used for handling SUP/IFO */
    private static SupDVD supDVD;
    /** Used for common handling of either SUPs */
    private static Substream substream;

    /** Array of subpictures used for editing and export */
    private static SubPicture subPictures[];

    /** Luminance thresholds for VobSub export */
    private static int lumThr[] = {210, 160};
    /** Alpha threshold for VobSub export */
    private static int alphaThr = 80;

    /** Default output mode */
    private static final OutputMode DEFAULT_OUTPUT_MODE = OutputMode.VOBSUB;
    /** Output mode used for next export */
    private static boolean outModeSet;
    /** Output mode used for next export */
    private static OutputMode outMode = DEFAULT_OUTPUT_MODE;
    /** Input mode used for last import */
    private static InputMode inMode = InputMode.VOBSUB;
    /** Resolution used for export */
    private static Resolution resolutionTrg = DEFAULT_TARGET_RESOLUTION;

    /** Flag that defines whether to convert frame rates or nowt*/
    private static boolean convertFPS = CONVERTS_FRAMERATE_BY_DEFAULT;
    /** Flag that defines whether to convert resolution or not */
    private static boolean convertResolution = CONVERT_RESOLUTION_BY_DEFAULT;
    /** Flag that defines whether to export only subpictures marked as "forced" */
    private static boolean exportForced;
    /** State that defined whether to set/clear the forced flag for all captions */
    private static ForcedFlagState forceAll = ForcedFlagState.KEEP;
    /** Flag that defines whether to swap Cr/Cb components when loading a SUP */
    private static boolean swapCrCb;
    /** Flag that defines whether to fix subpictures with a display time shorter than minTimePTS */
    private static boolean fixShortFrames = FIX_SHORT_FRAMES_BY_DEFAULT;
    /** Source frame rate is certain */
    private static boolean fpsSrcCertain;
    /** Source frame rate */
    private static double fpsSrc = DEFAULT_SOURCE_FRAMERATE;
    /** Source frame rate set via command line */
    private static boolean fpsSrcSet;
    /** Target frame rate */
    private static double fpsTrg = DEFAULT_TARGET_FRAMERATE;
    /** Delay to apply to target in 90kHz resolution */
    private static int delayPTS = DEFAULT_PTS_DELAY;
    /** Minimum display time for subpictures in 90kHz resolution */
    private static int minTimePTS = DEAFULT_MIN_DISPLAY_TIME_PTS;
    /** Y coordinate crop offset - when exporting, the Y position will be decreased by this value */
    private static int cropOfsY = 0;
    /** Use BT.601 color model instead of BT.709 */
    private static boolean useBT601;
    /** paletteMode was set from command line */
    private static boolean paletteModeSet;
    /** Default palette creation mode */
    private static final PaletteMode DEFAULT_PALETTE_MODE = PaletteMode.CREATE_NEW;
    /** Palette creation mode */
    private static PaletteMode paletteMode = DEFAULT_PALETTE_MODE;
    /** Verbatim mode was set from command line */
    private static boolean verbatimSet;
    /** Verbatim output ? */
    private static boolean verbatim;
    /** Use src fps for trg if possible */
    private static boolean keepFps;
    /** Default Scaling Filter to use */
    private static final ScalingFilter DEFAULT_SCALING_FILTER = ScalingFilter.BILINEAR;
    /** Scaling Filter to use */
    private static ScalingFilter scalingFilter = DEFAULT_SCALING_FILTER;
    /** Scaling filter was set from command line */
    private static boolean scalingFilterSet;
    /** Two equal captions are merged of they are closer than 200ms (0.2*90000 = 18000) */
    private static int mergePTSdiff = 18000;
    /** mergePTSdiff was set from command line */
    private static boolean mergePTSdiffSet;
    /** Alpha threshold for cropping */
    private static int alphaCrop = 14;
    /** alphaCrop was set from command line */
    private static boolean alphaCropSet;
    /** Fix completely invisibly subtitles due to alpha=0 (SUB/IDX and SUP/IFO import only) */
    private static boolean fixZeroAlpha;
    /** fixZeroAlpha was set from command line */
    private static boolean fixZeroAlphaSet;
    /** WritePGCEditPal was set from command line */
    private static boolean writePGCEditPalSet;
    /** Write PGCEdit palette file on export */
    private static boolean writePGCEditPal;

    /** Factor to calculate height of one cinemascope bar from screen height */
    private static double cineBarFactor = 5.0/42;
    /** Additional y offset to consider when moving */
    private static int moveOffsetY = 10;
    /** Additional x offset to consider when moving */
    private static int moveOffsetX = 10;
    /** Move move in Y direction */
    private static CaptionMoveModeY moveModeY = CaptionMoveModeY.KEEP_POSITION;
    /** Move move in X direction */
    private static CaptionMoveModeX moveModeX = CaptionMoveModeX.KEEP_POSITION;
    /** Flag: move subtitle */
    private static boolean moveCaptions;
    /** flag: apply free scaling */
    private static boolean applyFreeScale = APPLY_FREE_SCALE_BY_DEFAULT;
    /** Free X scaling factor */
    private static double freeScaleX = DEAFULT_FREE_SCALE_X;
    /** Free Y scaling factor */
    private static double freeScaleY = DEFAULT_FREE_SCALE_Y;

    /** Current input stream ID */
    private static StreamID currentStreamID = StreamID.UNKNOWN;
    /** Full filename of current source SUP (needed for thread) */
    private static String fileName;

    /** Reference to main GUI class */
    private static MainFrameView mainFrame;
    /** Progress dialog for loading/exporting */
    private static Progress progress;
    /** Maximum absolute value for progress bar */
    private static int progressMax;
    /** Last relative value for progress bar */
    private static int progressLast;

    /** Number of errors */
    private static int errors;
    /** Number of warnings */
    private static int warnings;

    /** started from command line? */
    private static boolean cliMode = true;
    /** Functionality executed in the started thread */
    private static RunType runType;
    /** Thread state */
    private static CoreThreadState state = CoreThreadState.INACTIVE;
    /** Used to store exception thrown in the thread */
    private static Exception threadException;
    /** Semaphore to disable actions while changing component properties */
    private static volatile boolean ready;
    /** Semaphore for synchronization */
    private static final Object semaphore = new Object();

    /** Strings storing recently loaded files */
    private static ArrayList<String> recentFiles = new ArrayList<String>();

    /** Thread used for threaded import/export. */
    @Override
    public void run() {
        state = CoreThreadState.ACTIVE;
        threadException = null;
        try {
            switch (runType) {
                case CREATESUB:
                    writeSub(fileName);
                    break;
                case READSUP:
                    readSup(fileName);
                    break;
                case READVOBSUB:
                    readVobSub(fileName);
                    break;
                case READSUPIFO:
                    readSupIfo(fileName);
                    break;
                case READXML:
                    readXml(fileName);
                    break;
                case MOVEALL:
                    moveAllToBounds();
                    break;
            }
        } catch (Exception ex) {
            threadException = ex;
        } finally {
            state = CoreThreadState.INACTIVE;
        }
    }

    /**
     * Initialize the Core - call this before calling readSub or other Core functionality.
     * @param c Main object - needed to determine name of jar and path of ini file
     */
    public static void init(Object c) {
        // extract path of JAR from the class name
        // needed to store ini file in the same folder as the JAR
        // note: during development, the path is the path of the main class
        if (c != null) {
            // only executed in GUI part
            cliMode = false;
            String s = c.getClass().getName().replace('.','/') + ".class";
            URL url = c.getClass().getClassLoader().getResource(s);
            int pos;
            try {
                fnameProps = URLDecoder.decode(url.getPath(),"UTF-8");
            } catch (UnsupportedEncodingException ex) {
            }
            if (((pos=fnameProps.toLowerCase().indexOf("file:")) != -1)) {
                fnameProps = fnameProps.substring(pos+5);
            }
            if ((pos=fnameProps.toLowerCase().indexOf(s.toLowerCase())) != -1) {
                fnameProps = fnameProps.substring(0,pos);
            }
            // special handling for JAR
            s = FilenameUtils.separatorsToUnix(fnameProps.toLowerCase());
            pos = s.lastIndexOf(".jar");
            if (pos != -1) {
                pos = s.substring(0,pos).lastIndexOf('/');
                if (pos != -1) {
                    fnameProps = fnameProps.substring(0,pos+1);
                }
            }
            fnameProps += Core.INI_FILE;

            // read properties from ini file
            props = new Props();
            props.setHeader(APP_NAME_AND_VERSION+" settings - don't modify manually");
            props.load(fnameProps);

            if (!verbatimSet) {
                verbatim = Core.props.get("verbatim", "false").equals("true");
            } else {
                props.set("verbatim", verbatim?"true":"false");
            }

            if (!writePGCEditPalSet) {
                writePGCEditPal = Core.props.get("writePGCEditPal", "false").equals("true");
            } else {
                props.set("writePGCEditPal", writePGCEditPal?"true":"false");
            }

            if (!mergePTSdiffSet) {
                mergePTSdiff = Core.props.get("mergePTSdiff", 18000);
            } else {
                props.set("mergePTSdiff", mergePTSdiff);
            }

            if (!alphaCropSet) {
                alphaCrop = Core.props.get("alphaCrop", 14);
            } else {
                props.set("alphaCrop", alphaCrop);
            }

            if (!fixZeroAlphaSet) {
                fixZeroAlpha = Core.props.get("fixZeroAlpha", "false").equals("true");
            } else {
                props.set("fixZeroAlpha", fixZeroAlpha?"true":"false");
            }

            if (!scalingFilterSet) {
                String filter = props.get("filter", DEFAULT_SCALING_FILTER.toString());
                for (ScalingFilter sf : ScalingFilter.values()) {
                    if (sf.toString().equalsIgnoreCase(filter)) {
                        scalingFilter = sf;
                        break;
                    }
                }
            } else {
                props.set("filter", scalingFilter.toString());
            }

            if (!paletteModeSet) {
                String pMode = props.get("paletteMode", DEFAULT_PALETTE_MODE.toString());
                for (PaletteMode pm : PaletteMode.values()) {
                    if (pm.toString().equalsIgnoreCase(pMode)) {
                        paletteMode = pm;
                        break;
                    }
                }
            } else {
                props.set("paletteMode", paletteMode.toString());
            }

            if (!outModeSet) {
                String oMode = props.get("outputMode", DEFAULT_OUTPUT_MODE.toString());
                for (OutputMode om : OutputMode.values()) {
                    if (om.toString().equalsIgnoreCase(oMode)) {
                        outMode = om;
                        break;
                    }
                }
            } else {
                props.set("outputMode", outMode.toString());
            }

            // load recent list
            int i = 0;
            while (i < RECENT_FILE_COUNT && (s = props.get("recent_"+i, "")).length() > 0) {
                recentFiles.add(s);
                i++;
            }

            if (!convertResolutionSet) {
                convertResolution = restoreConvertResolution();
            }
            if (convertResolution && !resolutionTrgSet) {
                    resolutionTrg = restoreResolution();
            }
            if (!convertFpsSet) {
                convertFPS = restoreConvertFPS();
            }
            if (convertFPS) {
                if (!fpsSrcCertain && !fpsSrcSet) {
                    fpsSrc = restoreFpsSrc();
                }
                if (!fpsTrgSet) {
                    fpsTrg = restoreFpsTrg();
                }
            }
            if (!delayPtsSet) {
                delayPTS = Core.restoreDelayPTS();
            }
            if (!fixShortFramesSet) {
                fixShortFrames = Core.restoreFixShortFrames();
            }
            if (!minTimePtsSet) {
                minTimePTS = Core.restoreMinTimePTS();
            }
            if (!applyFreeScaleSet) {
                applyFreeScale = Core.restoreApplyFreeScale();
                if (applyFreeScale) {
                    freeScaleX = Core.restoreFreeScaleX();
                    freeScaleY = Core.restoreFreeScaleY();
                }
            }
        }
    }

    /**
     * Hook to be called when the first file was loaded via the GUI
     */
    public static void loadedHook() {
        fpsSrcSet = false; // disable CLI override
    }

    /**
     * Reset the core, close all files
     */
    public static void close() {
        ready = false;
        if (supBD != null) {
            supBD.close();
        }
        if (supHD != null) {
            supHD.close();
        }
        if (supXml != null) {
            supXml.close();
        }
        if (subDVD != null) {
            subDVD.close();
        }
        if (supDVD != null) {
            supDVD.close();
        }
    }

    /**
     * Shut down the Core (write properties, close files etc.).
     */
    public static void exit() {
        storeProps();
        if (supBD != null) {
            supBD.close();
        }
        if (supHD != null) {
            supHD.close();
        }
        if (supXml != null) {
            supXml.close();
        }
        if (subDVD != null) {
            subDVD.close();
        }
        if (supDVD != null) {
            supDVD.close();
        }
    }

    /**
     * Write properties
     */
    public static void storeProps() {
        if (props != null) {
            props.save(fnameProps);
        }
    }

    public static void addToRecentFiles(String filename) {
        int index = recentFiles.indexOf(filename);
        if (index != -1) {
            recentFiles.remove(index);
            recentFiles.add(0, filename);
        } else {
            recentFiles.add(0, filename);
            if (recentFiles.size() > RECENT_FILE_COUNT) {
                recentFiles.remove(recentFiles.size() - 1);
            }
        }
        for (int i=0; i < recentFiles.size(); i++) {
            props.set("recent_" + i, recentFiles.get(i));
        }
    }

    /**
     * Return list of recently loaded files
     * @return list of recently loaded files
     */
    public static ArrayList<String> getRecentFiles() {
        return recentFiles;
    }

    /**
     * Identifies a stream by examining the first two bytes.
     * @param id Byte array holding four bytes at minimum
     * @return StreamID
     */
    public static StreamID getStreamID(byte id[]) {
        StreamID sid;

        if (id[0]==0x50 && id[1]==0x47) {
            sid = StreamID.BDSUP;
        } else if (id[0]==0x53 && id[1]==0x50) {
            sid = StreamID.SUP;
        } else if (id[0]==0x00 && id[1]==0x00 && id[2]==0x01 && id[3]==(byte)0xba) {
            sid = StreamID.DVDSUB;
        } else if (id[0]==0x23 && id[1]==0x20 && id[2]==0x56 && id[3]==0x6f) {
            sid = StreamID.IDX;
        } else if (id[0]==0x3c && id[1]==0x3f && id[2]==0x78 && id[3]==0x6d) {
            sid = StreamID.XML;
        } else if (id[0]==0x44 && id[1]==0x56 && id[2]==0x44 && id[3]==0x56) {
            sid = StreamID.IFO;
        } else {
            sid = StreamID.UNKNOWN;
        }

        return sid;
    }

    /**
     * Synchronizes a time stamp in 90kHz resolution to the given frame rate.
     * @param t 	Time stamp in 90kHz resolution
     * @param fps	Frame rate
     * @return		Synchronized time stamp in 90kHz resolution
     */
    public static long syncTimePTS(long t, double fps) {
        long retval;
        // correct time stamps to fit to frames
        if (fps == Framerate.NTSC.getValue() || fps == Framerate.PAL.getValue() || fps == Framerate.FPS_24.getValue()) {
            // NTSC: 90000/(30000/1001) = 3003
            // PAL:  90000/25 = 3600
            // 24Hz: 90000/24 = 3750
            int tpfi = (int)((90000+(fps/2))/fps); // target time per frame in 90kHz
            int tpfh = tpfi/2;
            retval = ((t + tpfh)/tpfi)*tpfi;
        } else if (fpsTrg == Framerate.FPS_23_976.getValue()) {
            // 90000/(24000/1001) = 3753.75 = 15015/4
            retval = ((((t + 1877)*4)/15015)*15015)/4;
        } else {
            double tpf = (90000/fpsTrg); // target time per frame in 90kHz
            retval = (long)((long)(t/tpf)*tpf+0.5);
        }
        return retval;
    }

    /**
     * Read a subtitle stream in a thread and display the progress dialog.
     * @param fname		File name of subtitle stream to read
     * @param parent	Parent frame (needed for progress dialog)
     * @param sid       stream identifier
     * @throws Exception
     */
    public static void readStreamThreaded(String fname, JFrame parent, StreamID sid) throws Exception {
        boolean xml = FilenameUtils.getExtension(fname).equalsIgnoreCase("xml");
        boolean idx = FilenameUtils.getExtension(fname).equalsIgnoreCase("idx");
        boolean ifo = FilenameUtils.getExtension(fname).equalsIgnoreCase("ifo");

        fileName = fname;
        progressMax = (int)(new File(fname)).length();
        progressLast = 0;
        progress = new Progress(parent);
        progress.setTitle("Loading");
        progress.setText("Loading subtitle stream");
        if (xml || sid == StreamID.XML) {
            runType = RunType.READXML;
        } else if (idx || sid == StreamID.DVDSUB || sid == StreamID.IDX) {
            runType = RunType.READVOBSUB;
        } else if (ifo || sid == StreamID.IFO) {
            runType = RunType.READSUPIFO;
        } else {
            runType = RunType.READSUP;
        }

        currentStreamID = sid;

        // start thread
        Thread t = new Thread(new Core());
        t.start();
        progress.setVisible(true);
        while (t.isAlive()) {
            try  {
                Thread.sleep(500);
            } catch (InterruptedException ex) {
            }
        }
        state = CoreThreadState.INACTIVE;
        Exception ex = threadException;
        if (ex != null) {
            throw ex;
        }
    }

    /**
     * Write a VobSub or BD-SUP in a thread and display the progress dialog.
     * @param fname		File name of subtitle stream to create
     * @param parent	Parent frame (needed for progress dialog)
     * @throws Exception
     */
    public static void createSubThreaded(String fname, JFrame parent) throws Exception {
        fileName = fname;
        progressMax = substream.getNumFrames();
        progressLast = 0;
        progress = new Progress(parent);
        progress.setTitle("Exporting");
        if (Core.outMode == OutputMode.VOBSUB) {
            progress.setText("Exporting SUB/IDX");
        } else if (Core.outMode == OutputMode.BDSUP) {
            progress.setText("Exporting SUP(BD)");
        } else if (Core.outMode == OutputMode.XML) {
            progress.setText("Exporting XML/PNG");
        } else {
            progress.setText("Exporting SUP/IFO");
        }
        runType = RunType.CREATESUB;
        // start thread
        Thread t = new Thread(new Core());
        t.start();
        progress.setVisible(true);
        while (t.isAlive()) {
            try  {
                Thread.sleep(500);
            } catch (InterruptedException ex) {
            }
        }
        state = CoreThreadState.INACTIVE;
        Exception ex = threadException;
        if (ex != null) {
            throw ex;
        }
    }

    /**
     * Create the frame individual 4-color palette for VobSub mode.
     * @index Index of caption
     */
    private static void determineFramePal(int index) {
        if ((inMode != InputMode.VOBSUB && inMode != InputMode.SUPIFO) || paletteMode != PaletteMode.KEEP_EXISTING) {
            // get the primary color from the source palette
            int rgbSrc[] = substream.getPalette().getRGB(substream.getPrimaryColorIndex());

            // match with primary color from 16 color target palette
            // note: skip index 0 , primary colors at even positions
            // special treatment for index 1:  white
            Palette trgPallete = currentDVDPalette;
            int minDistance = 0xffffff; // init > 0xff*0xff*3 = 0x02fa03
            int colIdx = 0;
            for (int idx=1; idx<trgPallete.getSize(); idx+=2 )  {
                int rgb[] = trgPallete.getRGB(idx);
                // distance vector (skip sqrt)
                int rd = rgbSrc[0]-rgb[0];
                int gd = rgbSrc[1]-rgb[1];
                int bd = rgbSrc[2]-rgb[2];
                int distance = rd*rd+gd*gd+bd*bd;
                // new minimum distance ?
                if ( distance < minDistance) {
                    colIdx = idx;
                    minDistance = distance;
                    if (minDistance == 0) {
                        break;
                    }
                }
                // special treatment for index 1 (white)
                if (idx == 1) {
                    idx--; // -> continue with index = 2
                }
            }

            // set new frame palette
            int palFrame[] = new int[4];
            palFrame[0] = 0;        // black - transparent color
            palFrame[1] = colIdx;   // primary color
            if (colIdx == 1) {
                palFrame[2] = colIdx+2; // special handling: white + dark grey
            } else {
                palFrame[2] = colIdx+1; // darker version of primary color
            }
            palFrame[3] = 0;        // black - opaque

            subVobTrg.alpha = DEFAULT_ALPHA;
            subVobTrg.pal = palFrame;

            trgPal = SubDVD.decodePalette(subVobTrg, trgPallete);
        } else {
            // use palette from loaded VobSub or SUP/IFO
            Palette miniPal = new Palette(4, true);
            int alpha[];
            int palFrame[];
            SubstreamDVD substreamDVD;

            if (inMode == InputMode.VOBSUB) {
                substreamDVD = subDVD;
            } else {
                substreamDVD = supDVD;
            }

            alpha = substreamDVD.getFrameAlpha(index);
            palFrame = substreamDVD.getFramePal(index);

            for (int i=0; i < 4; i++) {
                int a = (alpha[i]*0xff)/0xf;
                if (a >= alphaCrop) {
                    miniPal.setARGB(i, currentSourceDVDPalette.getARGB(palFrame[i]));
                    miniPal.setAlpha(i, a);
                } else {
                    miniPal.setARGB(i, 0);
                }
            }
            subVobTrg.alpha = alpha;
            subVobTrg.pal = palFrame;
            trgPal = miniPal;
        }
    }

    /**
     * Read BD-SUP or HD-DVD-SUP.
     * @param fname File name
     * @throws CoreException
     */
    public static void readSup(String fname) throws CoreException {
        printX("Loading "+fname+"\n");
        resetErrors();
        resetWarnings();

        // try to find matching language idx if filename contains language string
        String fnl = FilenameUtils.getName(fname.toLowerCase());
        for (int i=0; i < LANGUAGES.length; i++) {
            if (fnl.contains(LANGUAGES[i][0].toLowerCase())) {
                languageIdx = i;
                printX("Selected language '"+LANGUAGES[i][0]+" ("+LANGUAGES[i][1]+")' by filename\n");
                break;
            }
        }

        // close existing substream
        if (substream != null) {
            substream.close();
        }

        // check first two byte to determine whether this is a BD-SUP or HD-DVD-SUP
        byte id[] = ToolBox.getFileID(fname, 2);
        if (id != null && id[0] == 0x50 && id[1] == 0x47) {
            supBD = new SupBD(fname);
            substream = supBD;
            supHD = null;
            inMode = InputMode.BDSUP;
        } else {
            supHD = new SupHD(fname);
            substream = supHD;
            supBD = null;
            inMode = InputMode.HDDVDSUP;
        }

        // decode first frame
        substream.decode(0);
        subVobTrg = new SubPictureDVD();

        // automatically set luminance thresholds for VobSub conversion
        int maxLum = substream.getPalette().getY()[substream.getPrimaryColorIndex()] & 0xff;
        lumThr = new int[2];
        if (maxLum > 30) {
            lumThr[0] = maxLum*2/3;
            lumThr[1] = maxLum/3;
        } else {
            lumThr[0] = 210;
            lumThr[1] = 160;
        }

        // try to detect source frame rate
        if (!fpsSrcSet) {      // CLI override
            if (substream == supBD) {
                fpsSrc = supBD.getFps(0);
                fpsSrcCertain = true;
                if (Core.keepFps) {
                    setFPSTrg(fpsSrc);
                }
            } else {
                // for HD-DVD we need to guess
                useBT601 = false;
                fpsSrcCertain = false;
                fpsSrc = Framerate.FPS_23_976.getValue();
            }
        }
    }

    /**
     * Read Sony BDN XML file.
     * @param fname File name
     * @throws CoreException
     */
    public static void readXml(String fname) throws CoreException {
        printX("Loading "+fname+"\n");
        resetErrors();
        resetWarnings();

        // close existing substream
        if (substream != null) {
            substream.close();
        }


        supXml = new SupXml(fname);
        substream = supXml;

        inMode = InputMode.XML;

        // decode first frame
        substream.decode(0);
        subVobTrg = new SubPictureDVD();

        // automatically set luminance thresholds for VobSub conversion
        int maxLum = substream.getPalette().getY()[substream.getPrimaryColorIndex()] & 0xff;
        lumThr = new int[2];
        if (maxLum > 30) {
            lumThr[0] = maxLum*2/3;
            lumThr[1] = maxLum/3;
        } else {
            lumThr[0] = 210;
            lumThr[1] = 160;
        }

        // find language idx
        for (int i=0; i < LANGUAGES.length; i++) {
            if (LANGUAGES[i][2].equalsIgnoreCase(supXml.getLanguage())) {
                languageIdx = i;
                break;
            }
        }

        // set frame rate
        if (!fpsSrcSet) {      // CLI override
            fpsSrc = supXml.getFps();
            fpsSrcCertain = true;
            if (Core.keepFps) {
                setFPSTrg(fpsSrc);
            }
        }
    }

    /**
     * Read VobSub.
     * @param fname File name
     * @throws CoreException
     */
    public static void readVobSub(String fname) throws CoreException {
        readDVDSubstream(fname, true);
    }

    /**
     * Read SUP/IFO.
     * @param fname File name
     * @throws CoreException
     */
    public static void readSupIfo(String fname) throws CoreException {
        readDVDSubstream(fname, false);
    }

    /**
     * Read VobSub or SUP/IFO.
     * @param fname File name
     * @param isVobSub True if SUB/IDX, false if SUP/IFO
     * @throws CoreException
     */
    private static void readDVDSubstream(String fname, boolean isVobSub) throws CoreException {
        printX("Loading " + fname + "\n");
        resetErrors();
        resetWarnings();

        // close existing substream
        if (substream != null) {
            substream.close();
        }

        SubstreamDVD substreamDVD;
        String fnI;
        String fnS;

        if (isVobSub) {
            // SUB/IDX
            if (currentStreamID == StreamID.DVDSUB) {
                fnS = fname;
                fnI = FilenameUtils.removeExtension(fname) + ".idx";
            } else {
                fnI = fname;
                fnS = FilenameUtils.removeExtension(fname) + ".sub";
            }
            subDVD = new SubDVD(fnS, fnI);
            substream = subDVD;
            inMode = InputMode.VOBSUB;
            substreamDVD = subDVD;
        } else {
            // SUP/IFO
            fnI = fname;
            fnS = FilenameUtils.removeExtension(fname) + ".sup";
            supDVD = new SupDVD(fnS, fnI);
            substream = supDVD;
            inMode = InputMode.SUPIFO;
            substreamDVD = supDVD;
        }

        // decode first frame
        substream.decode(0);
        subVobTrg = new SubPictureDVD();
        defaultSourceDVDPalette = substreamDVD.getSrcPalette();
        currentSourceDVDPalette = new Palette(defaultSourceDVDPalette);

        // automatically set luminance thresholds for VobSub conversion
        int primColIdx = substream.getPrimaryColorIndex();
        int yMax = substream.getPalette().getY()[primColIdx] & 0xff;
        lumThr = new int[2];
        if (yMax > 10) {
            // find darkest opaque color
            int yMin = yMax;
            for (int i=0; i < 4; i++) {
                int y = substream.getPalette().getY()[i] & 0xff;
                int a = substream.getPalette().getAlpha(i);
                if (y < yMin && a > alphaThr) {
                    yMin = y;
                }
            }
            lumThr[0] = yMin + (yMax-yMin)*9/10;
            lumThr[1] = yMin + (yMax-yMin)*3/10;
        } else {
            lumThr[0] = 210;
            lumThr[1] = 160;
        }

        languageIdx = substreamDVD.getLanguageIdx();

        // set frame rate
        if (!fpsSrcSet) {      // CLI override
            int h = substream.getSubPicture(0).height; //substream.getBitmap().getHeight();
            switch (h) {
                case 480:
                    fpsSrc = Framerate.NTSC.getValue();
                    useBT601 = true;
                    fpsSrcCertain = true;
                    break;
                case 576:
                    fpsSrc = Framerate.PAL.getValue();
                    useBT601 = true;
                    fpsSrcCertain = true;
                    break;
                default:
                    useBT601 = false;
                    fpsSrc = Framerate.FPS_23_976.getValue();
                    fpsSrcCertain = false;
            }
        }
    }


    /**
     * Check start and end time, fix overlaps etc.
     * @param idx			Index of subpicture (just for display)
     * @param subPic		Subpicture to check/fix
     * @param subPicNext	Next subpicture
     * @param subPicPrev	Previous subpicture
     */
    private static void validateTimes(int idx, SubPicture subPic, SubPicture subPicNext, SubPicture subPicPrev) {
        //long tpf = (long)(90000/fpsTrg); // time per frame
        long ts =  subPic.startTime;     // start time
        long te =  subPic.endTime;       // end time
        long delay = 5000*90;            // default delay for missing end time (5 seconds)

        idx += 1; // only used for display

        // get end time of last frame
        long te_last;
        if (subPicPrev != null) {
            te_last = subPicPrev.endTime;
        } else {
            te_last = -1;
        }

        if (ts < te_last) {
            printWarn("start time of frame "+idx+" < end of last frame -> fixed\n");
            ts = te_last;
        }

        // get start time of next frame
        long ts_next;
        if (subPicNext != null) {
            ts_next = subPicNext.startTime;
        } else {
            ts_next = 0;
        }

        if (ts_next == 0) {
            if ( te > ts) {
                ts_next = te;
            } else {
                // completely messed up:
                // end time and next start time are invalid
                ts_next = ts+delay;
            }
        }

        if (te <= ts) {
            if (te == 0) {
                printWarn("missing end time of frame "+idx+" -> fixed\n");
            } else {
                printWarn("end time of frame "+idx+" <= start time -> fixed\n");
            }
            te = ts+delay;
            if (te > ts_next) {
                te = ts_next;
            }
        } else if (te > ts_next) {
            printWarn("end time of frame "+idx+" > start time of next frame -> fixed\n");
            te = ts_next;
        }

        if (te - ts < minTimePTS) {
            if (fixShortFrames) {
                te = ts + minTimePTS;
                if (te > ts_next) {
                    te = ts_next;
                }
                printWarn("duration of frame "+idx+" was shorter than "+(ToolBox.formatDouble(minTimePTS/90.0))+"ms -> fixed\n");
            } else {
                printWarn("duration of frame "+idx+" is shorter than "+(ToolBox.formatDouble(minTimePTS/90.0))+"ms\n");
            }
        }

        if (subPic.startTime != ts) {
            subPic.startTime = syncTimePTS(ts, fpsTrg);
        }
        if (subPic.endTime != te) {
            subPic.endTime = syncTimePTS(te, fpsTrg);
        }
    }

    /**
     * Update width, height and offsets of target SubPicture.<br>
     * This is needed if cropping captions during decode (i.e. the source image size changes).
     * @param index Index of caption
     * @return true: image size has changed, false: image size didn't change.
     */
    private static boolean updateTrgPic(int index) {
        SubPicture picSrc = substream.getSubPicture(index);
        SubPicture picTrg = subPictures[index];
        double scaleX = (double)picTrg.width/picSrc.width;
        double scaleY = (double)picTrg.height/picSrc.height;
        double fx;
        double fy;
        if (applyFreeScale) {
            fx = freeScaleX;
            fy = freeScaleY;
        } else {
            fx = 1.0;
            fy = 1.0;
        }

        int wOld = picTrg.getImageWidth();
        int hOld = picTrg.getImageHeight();
        int wNew = (int)(picSrc.getImageWidth()  * scaleX * fx + 0.5);
        if (wNew < MIN_IMAGE_DIMENSION) {
            wNew = picSrc.getImageWidth();
        } else if (wNew > picTrg.width) {
            wNew = picTrg.width;
        }
        int hNew = (int)(picSrc.getImageHeight() * scaleY * fy + 0.5);
        if (hNew < MIN_IMAGE_DIMENSION) {
            hNew = picSrc.getImageHeight();
        } else if (hNew > picTrg.height) {
            hNew = picTrg.height;
        }
        picTrg.setImageWidth(wNew);
        picTrg.setImageHeight(hNew);
        if (wNew != wOld) {
            int xOfs = (int)(picSrc.getOfsX() * scaleX + 0.5);
            int spaceSrc = (int)((picSrc.width-picSrc.getImageWidth())*scaleX + 0.5);
            int spaceTrg = picTrg.width - wNew;
            xOfs += (spaceTrg - spaceSrc) / 2;
            if (xOfs < 0) {
                xOfs = 0;
            } else if (xOfs+wNew > picTrg.width) {
                xOfs = picTrg.width - wNew;
            }
            picTrg.setOfsX(xOfs);
        }
        if (hNew != hOld) {
            int yOfs = (int)(picSrc.getOfsY() * scaleY + 0.5);
            int spaceSrc = (int)((picSrc.height-picSrc.getImageHeight())*scaleY + 0.5);
            int spaceTrg = picTrg.height - hNew;
            yOfs += (spaceTrg - spaceSrc) / 2;
            if (yOfs+hNew > picTrg.height) {
                yOfs = picTrg.height - hNew;
            }
            picTrg.setOfsY(yOfs);
        }
        // was image cropped?
        return (wNew != wOld) || (hNew != hOld);
    }

    /**
     * Apply the state of forceAll to all captions
     */
    public static void setForceAll() {
        if (subPictures != null) {
            for (SubPicture subPicture : subPictures) {
                switch (forceAll) {
                    case SET:
                        subPicture.isforced = true;
                        break;
                    case CLEAR:
                        subPicture.isforced = false;
                        break;
                }
            }
        }
    }

    /**
     * Create a copy of the loaded subpicture information frames.<br>
     * Apply scaling and speedup/delay to the copied frames.<br>
     * Sync frames to target fps.
     */
    public static void scanSubtitles() {
        subPictures = new SubPicture[substream.getNumFrames()];
        double factTS = convertFPS ? fpsSrc / fpsTrg : 1.0;

        // change target resolution to source resolution if no conversion is needed
        if (!convertResolution && getNumFrames() > 0) {
            resolutionTrg = getResolution(getSubPictureSrc(0).width, getSubPictureSrc(0).height);
        }

        double fx;
        double fy;
        if (applyFreeScale) {
            fx = freeScaleX;
            fy = freeScaleY;
        } else {
            fx = 1.0;
            fy = 1.0;
        }

        // first run: clone source subpics, apply speedup/down,
        SubPicture picSrc;
        for (int i=0; i<subPictures.length; i++) {
            picSrc = substream.getSubPicture(i);
            subPictures[i] = picSrc.copy();
            long ts = picSrc.startTime;
            long te = picSrc.endTime;
            // copy time stamps and apply speedup/speeddown
            if (!convertFPS) {
                subPictures[i].startTime = ts+delayPTS;
                subPictures[i].endTime = te+delayPTS;
            } else {
                subPictures[i].startTime= (long)(ts*factTS+0.5)+delayPTS;
                subPictures[i].endTime = (long)(te*factTS+0.5)+delayPTS;
            }
            // synchronize to target frame rate
            subPictures[i].startTime = syncTimePTS(subPictures[i].startTime, fpsTrg);
            subPictures[i].endTime = syncTimePTS(subPictures[i].endTime, fpsTrg);

            // set forced flag
            SubPicture picTrg = subPictures[i];
            switch (forceAll) {
                case SET:
                    picTrg.isforced = true;
                    break;
                case CLEAR:
                    picTrg.isforced = false;
                    break;
            }

            double scaleX;
            double scaleY;
            if (convertResolution) {
                // adjust image sizes and offsets
                // determine scaling factors
                picTrg.width = getResolution(resolutionTrg)[0];
                picTrg.height = getResolution(resolutionTrg)[1];
                scaleX = (double)picTrg.width/picSrc.width;
                scaleY = (double)picTrg.height/picSrc.height;
            } else {
                picTrg.width = picSrc.width;
                picTrg.height = picSrc.height;
                scaleX = 1.0;
                scaleY = 1.0;
            }
            int w = (int)(picSrc.getImageWidth()  * scaleX * fx + 0.5);
            if (w < MIN_IMAGE_DIMENSION) {
                w = picSrc.getImageWidth();
            } else if (w > picTrg.width) {
                w = picTrg.width;
            }

            int h = (int)(picSrc.getImageHeight() * scaleY * fy + 0.5);
            if (h < MIN_IMAGE_DIMENSION) {
                h = picSrc.getImageHeight();
            } else if (h > picTrg.height) {
                h = picTrg.height;
            }
            picTrg.setImageWidth(w);
            picTrg.setImageHeight(h);

            int xOfs = (int)(picSrc.getOfsX() * scaleX + 0.5);
            int spaceSrc = (int)((picSrc.width-picSrc.getImageWidth())*scaleX + 0.5);
            int spaceTrg = picTrg.width - w;
            xOfs += (spaceTrg - spaceSrc) / 2;
            if (xOfs < 0) {
                xOfs = 0;
            } else if (xOfs+w > picTrg.width) {
                xOfs = picTrg.width - w;
            }
            picTrg.setOfsX(xOfs);

            int yOfs = (int)(picSrc.getOfsY() * scaleY + 0.5);
            spaceSrc = (int)((picSrc.height-picSrc.getImageHeight())*scaleY + 0.5);
            spaceTrg = picTrg.height - h;
            yOfs += (spaceTrg - spaceSrc) / 2;
            if (yOfs+h > picTrg.height) {
                yOfs = picTrg.height - h;
            }
            picTrg.setOfsY(yOfs);
        }

        // 2nd run: validate times
        SubPicture picPrev = null;
        SubPicture picNext;
        for (int i=0; i<subPictures.length; i++) {
            if (i < subPictures.length-1) {
                picNext = subPictures[i+1];
            } else {
                picNext = null;
            }
            picSrc = subPictures[i];
            validateTimes(i, subPictures[i], picNext, picPrev);
            picPrev = picSrc;
        }
    }

    /**
     * Same as scanSubtitles, but consider existing frame copies.<br>
     * Times and X/Y offsets of existing frames are converted to new settings.
     * @param resOld        Resolution of existing frames
     * @param fpsTrgOld     Target fps of existing frames
     * @param delayOld      Delay of existing frames
     * @param convertFpsOld ConverFPS setting for existing frames
     * @param fsXOld        Old free scaling factor in X direction
     * @param fsYOld        Old free scaling factor in Y direction
     */
    public static void reScanSubtitles(Resolution resOld, double fpsTrgOld, int delayOld, boolean convertFpsOld, double fsXOld, double fsYOld) {
        //SubPicture subPicturesOld[] = subPictures;
        //subPictures = new SubPicture[sup.getNumFrames()];
        SubPicture picOld;
        SubPicture picSrc;
        double factTS;
        double factX;
        double factY;
        double fsXNew;
        double fsYNew;

        if (applyFreeScale) {
            fsXNew = freeScaleX;
            fsYNew = freeScaleY;
        } else {
            fsXNew = 1.0;
            fsYNew = 1.0;
        }

        if (convertFPS && !convertFpsOld) {
            factTS = fpsSrc / fpsTrg;
        } else if (!convertFPS && convertFpsOld) {
            factTS = fpsTrgOld / fpsSrc;
        } else if (convertFPS && convertFpsOld && (fpsTrg != fpsTrgOld)) {
            factTS = fpsTrgOld / fpsTrg;
        } else {
            factTS = 1.0;
        }

        // change target resolution to source resolution if no conversion is needed
        if (!convertResolution && getNumFrames() > 0) {
            resolutionTrg = getResolution(getSubPictureSrc(0).width, getSubPictureSrc(0).height);
        }

        if (resOld != resolutionTrg) {
            int rOld[] = getResolution(resOld);
            int rNew[] = getResolution(resolutionTrg);
            factX = (double)rNew[0]/(double)rOld[0];
            factY = (double)rNew[1]/(double)rOld[1];
        } else {
            factX = 1.0;
            factY = 1.0;
        }

        // first run: clone source subpics, apply speedup/down,
        for (int i=0; i < subPictures.length; i++) {
            picOld = subPictures[i];
            picSrc = substream.getSubPicture(i);
            subPictures[i] = picOld.copy();

            // set forced flag
            switch (forceAll) {
                case SET:
                    subPictures[i].isforced = true;
                    break;
                case CLEAR:
                    subPictures[i].isforced = false;
                    break;
            }

            long ts = picOld.startTime;
            long te = picOld.endTime;
            // copy time stamps and apply speedup/speeddown
            if (factTS == 1.0) {
                subPictures[i].startTime = ts-delayOld+delayPTS;
                subPictures[i].endTime = te-delayOld+delayPTS;
            } else {
                subPictures[i].startTime= (long)(ts*factTS+0.5)-delayOld+delayPTS;
                subPictures[i].endTime = (long)(te*factTS+0.5)-delayOld+delayPTS;
            }
            // synchronize to target frame rate
            subPictures[i].startTime = syncTimePTS(subPictures[i].startTime, fpsTrg);
            subPictures[i].endTime = syncTimePTS(subPictures[i].endTime, fpsTrg);
            // adjust image sizes and offsets
            // determine scaling factors
            double scaleX;
            double scaleY;
            if (convertResolution) {
                subPictures[i].width = getResolution(resolutionTrg)[0];
                subPictures[i].height = getResolution(resolutionTrg)[1];
                scaleX = (double)subPictures[i].width/picSrc.width;
                scaleY = (double)subPictures[i].height/picSrc.height;
            } else {
                subPictures[i].width = picSrc.width;
                subPictures[i].height = picSrc.height;
                scaleX = 1.0;
                scaleY = 1.0;
            }

            int w = (int)(picSrc.getImageWidth()  * scaleX * fsXNew + 0.5);
            if (w < MIN_IMAGE_DIMENSION) {
                w = picSrc.getImageWidth();
            } else if (w > subPictures[i].width) {
                w = subPictures[i].width;
                fsXNew = (double)w / (double)picSrc.getImageWidth() / scaleX;
            }
            int h = (int)(picSrc.getImageHeight() * scaleY * fsYNew + 0.5);
            if (h < MIN_IMAGE_DIMENSION) {
                h = picSrc.getImageHeight();
            } else if (h > subPictures[i].height) {
                h = subPictures[i].height;
                fsYNew = (double)h / (double)picSrc.getImageHeight() / scaleY;
            }

            subPictures[i].setImageWidth(w);
            subPictures[i].setImageHeight(h);

            // correct ratio change
            int xOfs = (int)(picOld.getOfsX()*factX + 0.5);
            if (fsXNew != fsXOld) {
                int spaceTrgOld = (int)((picOld.width - picOld.getImageWidth())*factX + 0.5);
                int spaceTrg    = subPictures[i].width - w;
                xOfs += (spaceTrg - spaceTrgOld) / 2;
            }
            if (xOfs < 0) {
                xOfs = 0;
            } else if (xOfs+w > subPictures[i].width) {
                xOfs = subPictures[i].width - w;
            }
            subPictures[i].setOfsX(xOfs);

            int yOfs = (int)(picOld.getOfsY()*factY + 0.5);
            if (fsYNew != fsYOld) {
                int spaceTrgOld = (int)((picOld.height - picOld.getImageHeight())*factY + 0.5);
                int spaceTrg = subPictures[i].height - h;
                yOfs += (spaceTrg - spaceTrgOld) / 2;
            }
            if (yOfs < 0) {
                yOfs = 0;
            }
            if (yOfs+h > subPictures[i].height) {
                yOfs = subPictures[i].height - h;
            }
            subPictures[i].setOfsY(yOfs);

            // fix erase patches
            double fx = factX * fsXNew / fsXOld;
            double fy = factY * fsYNew / fsYOld;
            ArrayList<ErasePatch> erasePatches = subPictures[i].erasePatch;
            if (erasePatches != null) {
                for (int j = 0; j < erasePatches.size(); j++) {
                    ErasePatch ep = erasePatches.get(j);
                    int x = (int)(ep.x * fx + 0.5);
                    int y = (int)(ep.y * fy + 0.5);
                    int width = (int)(ep.width * fx + 0.5);
                    int height = (int)(ep.height * fy + 0.5);
                    erasePatches.set(j, new ErasePatch(x, y, width, height));
                }
            }
        }

        // 2nd run: validate times (not fully necessary, but to avoid overlap due to truncation
        SubPicture subPicPrev = null;
        SubPicture subPicNext;

        for (int i=0; i<subPictures.length; i++) {
            if (i < subPictures.length-1) {
                subPicNext = subPictures[i+1];
            } else {
                subPicNext = null;
            }

            picOld = subPictures[i];
            validateTimes(i, subPictures[i], subPicNext, subPicPrev);
            subPicPrev = picOld;
        }
    }

    /**
     * Convert source subpicture image to target subpicture image.
     * @param index			Index of subtitle to convert
     * @param displayNum	Subtitle number to display (needed for forced subs)
     * @param displayMax	Maximum subtitle number to display (needed for forced subs)
     * @throws CoreException
     */
    public static void convertSup(int index, int displayNum, int displayMax) throws CoreException{
        convertSup(index, displayNum, displayMax, false);
    }

    /**
     * Convert source subpicture image to target subpicture image.
     * @param index			Index of subtitle to convert
     * @param displayNum	Subtitle number to display (needed for forced subs)
     * @param displayMax	Maximum subtitle number to display (needed for forced subs)
     * @param skipScaling   true: skip bitmap scaling and palette transformation (used for moving captions)
     * @throws CoreException
     */
    private static void convertSup(int index, int displayNum, int displayMax, boolean skipScaling) throws CoreException{
        int w,h;
        int startOfs = (int)substream.getStartOffset(index);
        SubPicture subPic = substream.getSubPicture(index);

        printX("Decoding frame "+displayNum+"/"+displayMax+((substream == supXml)?"\n":(" at offset "+ToolBox.toHexLeftZeroPadded(startOfs,8)+"\n")));

        synchronized (semaphore) {
            substream.decode(index);
            w = subPic.getImageWidth();
            h = subPic.getImageHeight();
            if (outMode == OutputMode.VOBSUB || outMode == OutputMode.SUPIFO) {
                determineFramePal(index);
            }
            updateTrgPic(index);
        }
        SubPicture picTrg = subPictures[index];
        picTrg.wasDecoded = true;

        int trgWidth = picTrg.getImageWidth();
        int trgHeight = picTrg.getImageHeight();
        if (trgWidth < MIN_IMAGE_DIMENSION || trgHeight < MIN_IMAGE_DIMENSION || w < MIN_IMAGE_DIMENSION || h < MIN_IMAGE_DIMENSION) {
            // don't scale to avoid division by zero in scaling routines
            trgWidth = w;
            trgHeight = h;
        }

        if (!skipScaling) {
            ResampleFilter f;
            switch (scalingFilter) {
                case BELL:
                    f = getBellFilter();
                    break;
                case BICUBIC:
                    f = getBiCubicFilter();
                    break;
                case BICUBIC_SPLINE:
                    f = getBSplineFilter();
                    break;
                case HERMITE:
                    f = getHermiteFilter();
                    break;
                case LANCZOS3:
                    f = getLanczos3Filter();
                    break;
                case TRIANGLE:
                    f = getTriangleFilter();
                    break;
                case MITCHELL:
                    f = getMitchellFilter();
                    break;
                default:
                    f = null;
            }

            Bitmap tBm;
            Palette tPal = trgPal;
            // create scaled bitmap
            if (outMode == OutputMode.VOBSUB || outMode == OutputMode.SUPIFO) {
                // export 4 color palette
                if (w==trgWidth && h==trgHeight) {
                    // don't scale at all
                    if ( (inMode == InputMode.VOBSUB || inMode == InputMode.SUPIFO) && paletteMode == PaletteMode.KEEP_EXISTING) {
                        tBm = substream.getBitmap(); // no conversion
                    } else {
                        tBm = substream.getBitmap().getBitmapWithNormalizedPalette(substream.getPalette().getAlpha(), alphaThr, substream.getPalette().getY(), lumThr); // reduce palette
                    }
                } else {
                    // scale up/down
                    if ((inMode == InputMode.VOBSUB || inMode == InputMode.SUPIFO) && paletteMode == PaletteMode.KEEP_EXISTING) {
                        // keep palette
                        if (f != null) {
                            tBm = substream.getBitmap().scaleFilter(trgWidth, trgHeight, substream.getPalette(), f);
                        } else {
                            tBm = substream.getBitmap().scaleBilinear(trgWidth, trgHeight, substream.getPalette());
                        }
                    } else {
                        // reduce palette
                        if (f != null) {
                            tBm = substream.getBitmap().scaleFilterLm(trgWidth, trgHeight, substream.getPalette(), alphaThr, lumThr, f);
                        } else {
                            tBm = substream.getBitmap().scaleBilinearLm(trgWidth, trgHeight, substream.getPalette(), alphaThr, lumThr);
                        }
                    }
                }
            } else {
                // export (up to) 256 color palette
                tPal = substream.getPalette();
                if (w==trgWidth && h==trgHeight) {
                    tBm = substream.getBitmap(); // no scaling, no conversion
                } else {
                    // scale up/down
                    if (paletteMode == PaletteMode.KEEP_EXISTING) {
                        // keep palette
                        if (f != null) {
                            tBm = substream.getBitmap().scaleFilter(trgWidth, trgHeight, substream.getPalette(), f);
                        } else {
                            tBm = substream.getBitmap().scaleBilinear(trgWidth, trgHeight, substream.getPalette());
                        }
                    } else {
                        // create new palette
                        boolean dither = paletteMode == PaletteMode.CREATE_DITHERED;
                        BitmapWithPalette pb;
                        if (f != null) {
                            pb = substream.getBitmap().scaleFilter(trgWidth, trgHeight, substream.getPalette(), f, dither);
                        } else {
                            pb = substream.getBitmap().scaleBilinear(trgWidth, trgHeight, substream.getPalette(), dither);
                        }
                        tBm = pb.bitmap;
                        tPal = pb.palette;
                    }
                }
            }
            if (picTrg.erasePatch != null) {
                trgBitmapUnpatched = new Bitmap(tBm);
                int col = tPal.getIndexOfMostTransparentPaletteEntry();
                for (ErasePatch ep : picTrg.erasePatch) {
                    tBm.fillRectangularWithColorIndex(ep.x, ep.y, ep.width, ep.height, (byte)col);
                }
            } else {
                trgBitmapUnpatched = tBm;
            }
            trgBitmap = tBm;
            trgPal = tPal;

        }

        if (cliMode) {
            moveToBounds(picTrg, displayNum, cineBarFactor, moveOffsetX, moveOffsetY, moveModeX, moveModeY, cropOfsY);
        }
    }

    /**
     * Create BD-SUP or VobSub or Xml.
     * @param fname File name of SUP/SUB/XML to create
     * @throws CoreException
     */
    public static void writeSub(String fname) throws CoreException {
        BufferedOutputStream out = null;
        ArrayList<Integer> offsets = null;
        ArrayList<Integer> timestamps = null;
        int frameNum = 0;
        int maxNum;
        String fn = "";

        // handling of forced subtitles
        if (exportForced) {
            maxNum = countForcedIncluded();
        } else {
            maxNum = countIncluded();
        }

        try {
            // handle file name extensions depending on mode
            if (outMode == OutputMode.VOBSUB) {
                fname = FilenameUtils.removeExtension(fname) + ".sub";
                out = new BufferedOutputStream(new FileOutputStream(fname));
                offsets = new ArrayList<Integer>();
                timestamps = new ArrayList<Integer>();
            } else if (outMode == OutputMode.SUPIFO) {
                fname = FilenameUtils.removeExtension(fname) + ".sup";
                out = new BufferedOutputStream(new FileOutputStream(fname));
            } else if (outMode == OutputMode.BDSUP) {
                fname = FilenameUtils.removeExtension(fname) + ".sup";
                out = new BufferedOutputStream(new FileOutputStream(fname));
            } else {
                fn = FilenameUtils.removeExtension(fname);
                fname = fn+".xml";
            }
            printX("\nWriting "+fname+"\n");
            resetErrors();
            resetWarnings();

            // main loop
            int offset = 0;
            for (int i=0; i < substream.getNumFrames(); i++) {
                // for threaded version
                if (isCancelled()) {
                    throw new CoreException("Cancelled by user!");
                }
                // for threaded version (progress bar);
                setProgress(i);
                //
                if (!subPictures[i].exclude && (!exportForced || subPictures[i].isforced )) {
                    if (outMode == OutputMode.VOBSUB) {
                        offsets.add(offset);
                        convertSup(i, frameNum/2+1, maxNum);
                        subVobTrg.copyInfo(subPictures[i]);
                        byte buf[] = SubDVD.createSubFrame(subVobTrg, trgBitmap);
                        out.write(buf);
                        offset += buf.length;
                        timestamps.add((int)subPictures[i].startTime);
                    } else if (outMode == OutputMode.SUPIFO) {
                        convertSup(i, frameNum/2+1, maxNum);
                        subVobTrg.copyInfo(subPictures[i]);
                        byte buf[] = SupDVD.createSupFrame(subVobTrg, trgBitmap);
                        out.write(buf);
                    } else if (outMode == OutputMode.BDSUP) {
                        subPictures[i].compNum = frameNum;
                        convertSup(i, frameNum/2+1, maxNum);
                        byte buf[] = SupBD.createSupFrame(subPictures[i], trgBitmap, trgPal);
                        out.write(buf);
                    } else {
                        // Xml
                        convertSup(i, frameNum/2+1, maxNum);
                        String fnp = SupXml.getPNGname(fn,i+1);
                        //File file = new File(fnp);
                        //ImageIO.write(trgBitmap.getImage(trgPal), "png", file);
                        out = new BufferedOutputStream(new FileOutputStream(fnp));
                        EnhancedPngEncoder pngEncoder= new EnhancedPngEncoder(trgBitmap.getImage(trgPal.getColorModel()));
                        byte buf[] = pngEncoder.pngEncode(true);
                        out.write(buf);
                        out.close();

                    }
                    frameNum+=2;
                }
            }
        } catch (IOException ex) {
            throw new CoreException(ex.getMessage());
        }
        finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (IOException ex) {
            }
        }

        boolean importedDVDPalette;
        importedDVDPalette = (inMode == InputMode.VOBSUB) || (inMode == InputMode.SUPIFO);

        Palette trgPallete = null;
        if (outMode == OutputMode.VOBSUB) {
            // VobSub - write IDX
            /* return offets as array of ints */
            int ofs[] = new int[offsets.size()];
            for (int i=0; i<ofs.length; i++) {
                ofs[i] = offsets.get(i);
            }
            int ts[] = new int[timestamps.size()];
            for (int i=0; i<ts.length; i++) {
                ts[i] = timestamps.get(i);
            }
            fname = FilenameUtils.removeExtension(fname) + ".idx";
            printX("\nWriting "+fname+"\n");
            if (!importedDVDPalette || paletteMode != PaletteMode.KEEP_EXISTING) {
                trgPallete = currentDVDPalette;
            } else {
                trgPallete = currentSourceDVDPalette;
            }
            SubDVD.writeIdx(fname, subPictures[0], ofs, ts, trgPallete);
        } else if (outMode == OutputMode.XML) {
            // XML - write ML
            printX("\nWriting "+fname+"\n");
            SupXml.writeXml(fname, subPictures);
        } else if (outMode == OutputMode.SUPIFO) {
            // SUP/IFO - write IFO
            if (!importedDVDPalette || paletteMode != PaletteMode.KEEP_EXISTING) {
                trgPallete = currentDVDPalette;
            } else {
                trgPallete = currentSourceDVDPalette;
            }
            fname = FilenameUtils.removeExtension(fname) + ".ifo";
            printX("\nWriting "+fname+"\n");
            SupDVD.writeIFO(fname, subPictures[0], trgPallete);
        }

        // only possible for SUB/IDX and SUP/IFO (else there is no public palette)
        if (trgPallete != null && writePGCEditPal) {
            String fnp = FilenameUtils.removeExtension(fname) + ".txt";
            printX("\nWriting "+fnp+"\n");
            writePGCEditPal(fnp, trgPallete);
        }

        state = CoreThreadState.FINISHED;
    }

    /**
     * Get default frame rate for given resolution.
     * @param r Output resolution
     * @return Default frame rate for resolution r
     */
    public static double getDefaultFPS(Resolution r) {
        double fps;
        switch (getResolution(r)[1]) {
            case 480:
                fps = Framerate.NTSC.getValue();
                break;
            case 576:
                fps = Framerate.PAL.getValue();
                break;
            default:
                fps = Framerate.FPS_23_976.getValue();
        }
        return fps;
    }

    /**
     * Convert a string containing a frame rate to a double representation.
     * @param s	String containing a frame
     * @return	Double representation of the frame rate
     */
    public static double getFPS(String s) {
        // first check the string
        s = s.toLowerCase().trim();
        if (s.equals("pal")  || s.equals("25p") || s.equals("25")) {
            return Framerate.PAL.getValue();
        }
        if (s.equals("ntsc") || s.equals("30p") || s.equals("29.97") || s.equals("29.970")) {
            return Framerate.NTSC.getValue();
        }
        if (s.equals("24p")  || s.equals("23.976")) {
            return Framerate.FPS_23_976.getValue();
        }
        if (s.equals("23.975")) {
            return Framerate.FPS_23_975.getValue();
        }
        if (s.equals("24")) {
            return Framerate.FPS_24.getValue();
        }
        if (s.equals("50i")  || s.equals("50")) {
            return Framerate.PAL_I.getValue();
        }
        if (s.equals("60i")  || s.equals("59.94")) {
            return Framerate.NTSC_I.getValue();
        }

        // now check the number
        double d;
        try {
            d = Double.parseDouble(s);
        } catch (NumberFormatException ex) {
            return -1.0;
        }
        if (Math.abs(d - Framerate.FPS_23_975.getValue()) < 0.001) {
            return Framerate.FPS_23_975.getValue();
        }
        if (Math.abs(d - Framerate.FPS_23_976.getValue()) < 0.001) {
            return Framerate.FPS_23_976.getValue();
        }
        if (Math.abs(d - Framerate.FPS_24.getValue()) < 0.001) {
            return Framerate.FPS_24.getValue();
        }
        if (Math.abs(d - Framerate.PAL.getValue()) < 0.001) {
            return Framerate.PAL.getValue();
        }
        if (Math.abs(d - Framerate.NTSC.getValue()) < 0.001) {
            return Framerate.NTSC.getValue();
        }
        if (Math.abs(d - Framerate.NTSC_I.getValue()) < 0.001) {
            return Framerate.NTSC_I.getValue();
        }
        if (Math.abs(d - Framerate.PAL_I.getValue()) < 0.001) {
            return Framerate.PAL_I.getValue();
        }
        return d;
    }

    /**
     * Move all subpictures into or outside given bounds in a thread and display the progress dialog.
     * @param parent	Parent frame (needed for progress dialog)
     * @throws Exception
     */
    public static void moveAllThreaded(JFrame parent) throws Exception {
        progressMax = substream.getNumFrames();
        progressLast = 0;
        progress = new Progress(parent);
        progress.setTitle("Moving");
        progress.setText("Moving all captions");
        runType = RunType.MOVEALL;
        // start thread
        Thread t = new Thread(new Core());
        t.start();
        progress.setVisible(true);
        while (t.isAlive()) {
            try  {
                Thread.sleep(500);
            } catch (InterruptedException ex) {
            }
        }
        state = CoreThreadState.INACTIVE;
        Exception ex = threadException;
        if (ex != null) {
            throw ex;
        }
    }

    /**
     * Move all subpictures into or outside given bounds.
     * @throws CoreException
     */
    public static void moveAllToBounds() throws CoreException {
        String sy = null;
        switch (moveModeY) {
            case MOVE_INSIDE_BOUNDS:
                sy = "inside";
                break;
            case MOVE_OUTSIDE_BOUNDS:
                sy = "outside";
                break;
        }
        String sx = null;
        switch (moveModeX) {
            case CENTER:
                sx = "center vertically";
                break;
            case LEFT:
                sx = "left";
                break;
            case RIGHT:
                sx = "right";
        }
        String s = "Moving captions ";
        if (sy!= null) {
            s += sy + " cinemascope bars";
            if (sx != null) {
                 s += " and to the " + sx;
            }
            print(s+".\n");
        } else if (sx != null) {
            print(s+"to the "+sx+".\n");
        }

        if (!cliMode) {
            // in CLI mode, moving is done during export
            for (int idx=0; idx<subPictures.length; idx++) {
                setProgress(idx);
                if (!subPictures[idx].wasDecoded) {
                    convertSup(idx, idx+1, subPictures.length, true);
                }
                moveToBounds(subPictures[idx], idx+1, cineBarFactor, moveOffsetX, moveOffsetY, moveModeX, moveModeY, cropOfsY);
            }
        }
    }

    /**
     * Move subpicture into or outside given bounds.
     * @param pic         SubPicture object containing coordinates and size
     * @param idx         Index (only used for display)
     * @param barFactor   Factor to calculate cinemascope bar height from screen height
     * @param offsetX     X offset to consider when moving
     * @param offsetY     Y offset to consider when moving
     * @param mmx         Move mode in X direction
     * @param mmy         Move mode in Y direction
     * @param cropOffsetY Number of lines to crop from bottom and top
     */
    public static void moveToBounds(SubPicture pic, int idx, double barFactor, int offsetX, int offsetY,
            CaptionMoveModeX mmx, CaptionMoveModeY mmy, int cropOffsetY) {

        int barHeight = (int)(pic.height * barFactor + 0.5);
        int y1 = pic.getOfsY();
        int h = pic.height;
        int w = pic.width;
        int hi = pic.getImageHeight();
        int wi = pic.getImageWidth();
        int y2 = y1 + hi;
        CaptionType c;

        if (mmy != CaptionMoveModeY.KEEP_POSITION) {
            // move vertically
            if (y1 < h/2 && y2 < h/2) {
                c = CaptionType.UP;
            } else if (y1 > h/2 && y2 > h/2) {
                c = CaptionType.DOWN;
            } else {
                c = CaptionType.FULL;
            }

            switch (c) {
                case FULL:
                    // maybe add scaling later, but for now: do nothing
                    printWarn("Caption "+idx+" not moved (too large)\n");
                    break;
                case UP:
                    if (mmy == CaptionMoveModeY.MOVE_INSIDE_BOUNDS)
                        pic.setOfsY(barHeight+offsetY);
                    else
                        pic.setOfsY(offsetY);
                    print("Caption "+idx+" moved to y position "+pic.getOfsY()+"\n");
                    break;
                case DOWN:
                    if (mmy == CaptionMoveModeY.MOVE_INSIDE_BOUNDS) {
                        pic.setOfsY(h-barHeight-offsetY-hi);
                    } else {
                        pic.setOfsY(h-offsetY-hi);
                    }
                    print("Caption "+idx+" moved to y position "+pic.getOfsY()+"\n");
                    break;
            }
            if (pic.getOfsY() < cropOffsetY) {
                pic.getOfsY();
            } else {
                int yMax = pic.height - pic.getImageHeight() - cropOffsetY;
                if (pic.getOfsY() > yMax) {
                    pic.setOfsY(yMax);
                }
            }
        }
        // move horizontally
        switch (mmx) {
            case LEFT:
                if (w-wi >= offsetX) {
                    pic.setOfsX(offsetX);
                } else {
                    pic.setOfsX((w-wi)/2);
                }
                break;
            case RIGHT:
                if (w-wi >= offsetX) {
                    pic.setOfsX(w-wi-offsetX);
                } else {
                    pic.setOfsX((w-wi)/2);
                }
                break;
            case CENTER:
                pic.setOfsX((w-wi)/2);
                break;
        }
    }

    /**
     * Print string to console or console window (only printed in verbatim mode).
     * @param s String containing message to print
     */
    public static void print(String s) {
        if (Core.verbatim) {
            if (mainFrame != null) {
                mainFrame.printOut(s);
            } else {
                System.out.print(s);
            }
        }
    }

    /**
     * Print string to console or console window (always printed).
     * @param s String containing message to print
     */
    public static void printX(String s) {
        if (mainFrame != null) {
            mainFrame.printOut(s);
        } else {
            System.out.print(s);
        }
    }

    /**
     * Print error string to console or console window (always printed).
     * @param s String containing error message to print
     */
    public static void printErr(String s) {
        errors++;
        s = "ERROR: " + s;
        if (mainFrame != null) {
            mainFrame.printErr(s);
        } else {
            System.out.print(s);
        }
    }

    /**
     * Print warning string to console or console window (always printed).
     * @param s String containing warning message to print
     */
    public static void printWarn(String s) {
        warnings++;
        s = "WARNING: "+s;
        if (mainFrame != null) {
            mainFrame.printWarn(s);
        } else {
            System.out.print(s);
        }
    }

    /**
     * Create PGCEdit palette file from given Palette.
     * @param fname File name
     * @param p     Palette
     * @throws CoreException
     */
    private static void writePGCEditPal(String fname, Palette p) throws CoreException {
        BufferedWriter out = null;
        try {
            out = new BufferedWriter(new FileWriter(fname));
            out.write("# Palette file for PGCEdit - colors given as R,G,B components (0..255)");
            out.newLine();
            for (int i=0; i < p.getSize(); i++) {
                int rgb[] = p.getRGB(i);
                out.write("Color "+i+"="+rgb[0]+", "+rgb[1]+", "+rgb[2]);
                out.newLine();
            }
        } catch (IOException ex) {
            throw new CoreException(ex.getMessage());
        }
        finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (IOException ex) {
            }
        }
    }

    /**
     * Count the number of forced subpictures to be exported.
     * @return Number of forced subpictures to be exported
     */
    private static int countForcedIncluded() {
        int n = 0;
        for (SubPicture pic : subPictures) {
            if (pic.isforced && !pic.exclude) {
                n++;
            }
        }
        return n;
    }

    /**
     * Count the number of subpictures to be exported.
     * @return Number of subpictures to be exported
     */
    private static int countIncluded() {
        int n = 0;
        for (SubPicture pic : subPictures) {
            if (!pic.exclude) {
                n++;
            }
        }
        return n;
    }


    /* Setters / Getters */

    /**
     * Get luminance thresholds.
     * @return Array of thresholds ( 0: med/high, 1: low/med )
     */
    public static int[] getLumThr() {
        return lumThr;
    }

    /**
     * Set luminance thresholds.
     * @param lt Array of luminance thresholds ( 0: med/high, 1: low/med )
     */
    public static void setLumThr(int[] lt) {
        lumThr = lt;
    }

    /**
     * Get alpha threshold.
     * @return Current alpha threshold
     */
    public static int getAlphaThr() {
        return alphaThr;
    }

    /**
     * Set alpha threshold.
     * @param at Alpha threshold
     */
    public static void setAlphaThr(int at) {
        alphaThr = at;
    }

    /**
     * Get Core ready state.
     * @return True if the Core is ready
     */
    public static boolean isReady() {
        return ready;
    }

    /**
     * Set Core ready state.
     * @param r true if the Core is ready
     */
    public static void setReady(boolean r) {
        ready = r;
    }

    /**
     * Get output resolution.
     * @return Current output resolution
     */
    public static Resolution getOutputResolution() {
        return resolutionTrg;
    }

    /**
     * Set output resolution.
     * @param r output resolution
     */
    public static void setOutputResolution(Resolution r) {
        resolutionTrg = r;
        if (props == null) {
            resolutionTrgSet = true;
        }
    }

    /**
     * Store output resolution.
     * @param r output resolution
     */
    public static void storeOutputResolution(Resolution r) {
        props.set("resolutionTrg", getResolutionName(r));
    }

    /**
     * get default value for frame rate conversion
     * @return default value for frame rate conversion
     */
    public static boolean getConvertFPSdefault() {
        return CONVERTS_FRAMERATE_BY_DEFAULT;
    }

    /**
     * get default value for resolution conversion
     * @return default value for resolution conversion
     */
    public static boolean getConvertResolutionDefault() {
        return CONVERT_RESOLUTION_BY_DEFAULT;
    }

    /**
     * get default target resolution
     * @return default target resolution
     */
    public static Resolution getResolutionDefault() {
        return DEFAULT_TARGET_RESOLUTION;
    }

    /**
     * get default value for source frame rate
     * @return default value for source frame rate
     */
    public static double getFpsSrcDefault() {
        return DEFAULT_SOURCE_FRAMERATE;
    }

    /**
     * get default value for target frame rate
     * @return default value for target frame rate
     */
    public static double getFpsTrgDefault() {
        return DEFAULT_TARGET_FRAMERATE;
    }

    /**
     * get default value for delay
     * @return default value delay
     */
    public static int getDelayPTSdefault() {
        return DEFAULT_PTS_DELAY;
    }

    /**
     * get default value for fixing short frame
     * @return default value for fixing short frame
     */
    public static boolean getFixShortFramesDefault() {
        return FIX_SHORT_FRAMES_BY_DEFAULT;
    }

    /**
     * get default value for minimum display time
     * @return default value for minimum display time
     */
    public static int getMinTimePTSdefault() {
        return DEAFULT_MIN_DISPLAY_TIME_PTS;
    }

    /**
     * get default value for applying of free scaling
     * @return default value for applying of free scaling
     */
    public static boolean getApplyFreeScaleDefault() {
        return APPLY_FREE_SCALE_BY_DEFAULT;
    }

    /**
     * get default value for free x scaling factor
     * @return default value for free x scaling factor
     */
    public static double getFreeScaleXdefault() {
        return DEAFULT_FREE_SCALE_X;
    }

    /**
     * get default value for free y scaling factor
     * @return default value for free y scaling factor
     */
    public static double getFreeScaleYdefault() {
        return DEFAULT_FREE_SCALE_Y;
    }

    /**
     * restore value for frame rate conversion
     * @return restored value for frame rate conversion
     */
    public static boolean restoreConvertFPS() {
        return props.get("convertFPS", convertFPS);
    }

    /**
     * restore value for resolution conversion
     * @return restored value for resolution conversion
     */
    public static boolean restoreConvertResolution() {
        return props.get("convertResolution", convertResolution);
    }

    /**
     * restore default target resolution
     * @return restored target resolution
     */
    public static Resolution restoreResolution() {
        String s = props.get("resolutionTrg", getResolutionName(resolutionTrg));
        for (Resolution r : Resolution.values()) {
            if (Core.getResolutionName(r).equalsIgnoreCase(s)) {
                    return r;
            }
        }
        return resolutionTrg;
    }


    /**
     * restore value for source frame rate
     * @return restored value for source frame rate
     */
    public static double restoreFpsSrc() {
        return getFPS(props.get("fpsSrc", String.valueOf(fpsSrc)));
    }

    /**
     * restore value for target frame rate
     * @return restored value for target frame rate
     */
    public static double restoreFpsTrg() {
        return getFPS(props.get("fpsTrg", String.valueOf(fpsTrg)));
    }

    /**
     * restore value for delay
     * @return restored value delay
     */
    public static int restoreDelayPTS() {
        return props.get("delayPTS", delayPTS);
    }

    /**
     * restore value for fixing short frame
     * @return restored value for fixing short frame
     */
    public static boolean restoreFixShortFrames() {
        return props.get("fixShortFrames", fixShortFrames);
    }

    /**
     * restore value for minimum display time
     * @return restored value for minimum display time
     */
    public static int restoreMinTimePTS() {
        return props.get("minTimePTS", minTimePTS);
    }

    /**
     * restore value for applying of free scaling
     * @return restored value for applying of free scaling
     */
    public static boolean restoreApplyFreeScale() {
        return props.get("applyFreeScale", applyFreeScale);
    }

    /**
     * restore value for free x scaling factor
     * @return restored value for free x scaling factor
     */
    public static double restoreFreeScaleX() {
        return props.get("freeScaleX", freeScaleX);
    }

    /**
     * restore value for free y scaling factor
     * @return restored value for free y scaling factor
     */
    public static double restoreFreeScaleY() {
        return props.get("freeScaleY", freeScaleY);
    }

    /**
     * Find the most fitting resolution for the given width and height
     * @param width screen width
     * @param height screen height
     * @return most fitting resolution
     */
    public static Resolution getResolution(int width, int height) {
        if (width <= Resolution.NTSC.getDimensions()[0] && height <= Resolution.NTSC.getDimensions()[1]) {
            return Resolution.NTSC;
        } else if (width <= Resolution.PAL.getDimensions()[0] && height <= Resolution.PAL.getDimensions()[1]) {
            return Resolution.PAL;
        } else if (width <= Resolution.HD_720.getDimensions()[0] && height <= Resolution.HD_720.getDimensions()[1]) {
            return Resolution.HD_720;
        } else if (width <= Resolution.HD_1440x1080.getDimensions()[0] && height <= Resolution.HD_1440x1080.getDimensions()[1]) {
            return Resolution.HD_1440x1080;
        } else {
            return Resolution.HD_1080;
        }
    }

    /**
     *  Force Core to cancel current operation.
     */
    public static void cancel() {
        state = CoreThreadState.CANCELED;
    }

    /**
     * Get cancel state.
     * @return True if the current operation was canceled
     */
    public static boolean isCancelled() {
        return state == CoreThreadState.CANCELED;
    }

    /**
     * Get Core state.
     * @return Current Core state
     */
    public static CoreThreadState getStatus() {
        return state;
    }

    /**
     * Get flag that tells whether or not to convert the frame rate.
     * @return Flag that tells whether or not to convert the frame rate
     */
    public static boolean getConvertFPS() {
        return convertFPS;
    }

    /**
     * Set flag that tells whether or not to convert the frame rate.
     * @param b True: convert frame rate
     */
    public static void setConvertFPS(boolean b) {
        convertFPS = b;
        if (props == null) {
            convertFpsSet = true;
        }
    }

    /**
     * Store flag that tells whether or not to convert the frame rate.
     * @param b True: convert frame rate
     */
    public static void storeConvertFPS(boolean b) {
        props.set("convertFPS", b);
    }

    /**
     * Get flag that tells whether or not to convert the resolution.
     * @return Flag that tells whether or not to convert the resolution
     */
    public static boolean getConvertResolution() {
        return convertResolution;
    }

    /**
     * Set flag that tells whether or not to convert the resolution.
     * @param b True: convert resolution
     */
    public static void setConvertResolution(boolean b) {
        convertResolution = b;
        if (props == null) {
            convertResolutionSet = true;
        }
    }

    /**
     * Store flag that tells whether or not to convert the resolution.
     * @param b True: convert resolution
     */
    public static void storeConvertResolution(boolean b) {
        props.set("convertResolution", b);
    }

    /**
     * Get flag that tells whether or not to export only forced subtitles.
     * @return Flag that tells whether or not to export only forced subtitles
     */
    public static boolean getExportForced () {
        return exportForced;
    }

    /**
     * Set flag that tells whether or not to export only forced subtitles.
     * @param b True: export only forced subtitles
     */
    public static void setExportForced(boolean b) {
        exportForced = b;
    }

    /**
     * Request setting of forced flag for all captions
     * @return current state
     */
    public static ForcedFlagState getForceAll() {
        return forceAll;
    }

    /**
     * Request setting of forced flag for all captions
     * @param f state to set
     */
    public static void setForceAll(ForcedFlagState f) {
        forceAll = f;
    }

    /**
     * Get source frame rate.
     * @return Source frame rate
     */
    public static double getFPSSrc() {
        return fpsSrc;
    }

    /**
     * Set source frame rate.
     * @param src Source frame rate
     */
    public static void setFPSSrc(double src) {
        fpsSrc = src;
        if (props == null) {
            // avoid overwriting of command line value
            fpsSrcSet = true;
            fpsSrcCertain = true;
        }
    }

    /**
     * Store source frame rate.
     * @param src Source frame rate
     */
    public static void storeFPSSrc(double src) {
        props.set("fpsSrc", src);
    }

    /**
     * Get target frame rate.
     * @return Target frame rate
     */
    public static double getFPSTrg() {
        return fpsTrg;
    }

    /**
     * Set target frame rate.
     * @param trg Target frame rate
     */
    public static void setFPSTrg(double trg) {
        fpsTrg = trg;
        delayPTS = (int)syncTimePTS(delayPTS, trg);
        minTimePTS = (int)syncTimePTS(minTimePTS, trg);
        if (props == null) {
            fpsTrgSet = true;
        }
    }

    /**
     * Store target frame rate.
     * @param trg Target frame rate
     */
    public static void storeFPSTrg(double trg) {
        props.set("fpsTrg", trg);
    }

    /**
     * Get delay to add to time stamps.
     * @return Delay in 90kHz resolution
     */
    public static int getDelayPTS() {
        return delayPTS;
    }

    /**
     * Set delay to add to time stamps.
     * @param delay Delay in 90kHz resolution
     */
    public static void setDelayPTS(int delay) {
        delayPTS = delay;
        if (props == null) {
            delayPtsSet = true;
        }
    }

    /**
     * Store delay to add to time stamps.
     * @param delay Delay in 90kHz resolution
     */
    public static void storeDelayPTS(int delay) {
        props.set("delayPTS", delay);
    }

    /**
     * Get reference to the main frame.
     * @return Reference to the main frame
     */
    public static MainFrameView getMainFrame() {
        return mainFrame;
    }

    /**
     * Set reference to the main frame.
     * @param mf Reference to the main frame
     */
    public static void setMainFrame(final MainFrameView mf) {
        mainFrame = mf;
    }

    /**
     * Get the number of errors.
     * @return Number of errors since last call to resetErrors
     */
    public static int getErrors() {
        return errors;
    }

    /**
     * Reset the number of errors.
     */
    public static void resetErrors() {
        errors = 0;
    }

    /**
     * Get the number of warnings.
     * @return Number of warnings since last call to resetWarnings
     */
    public static int getWarnings() {
        return warnings;
    }

    /**
     * Reset the number of warnings.
     */
    public static void resetWarnings() {
        warnings = 0;
    }

    /**
     * Set progress in progress bar.
     * @param p Subtitle index processed
     */
    public static void setProgress(int p) {
        if (progress != null) {
            int val = (int)(((long)p*100)/progressMax);
            if (val > progressLast) {
                progressLast = val;
                progress.setProgress(val);
            }
        }
    }

    /**
     * Get output mode.
     * @return Current output mode
     */
    public static OutputMode getOutputMode() {
        return outMode;
    }

    /**
     * Set output mode.
     * @param m Output mode
     */
    public static void setOutputMode(OutputMode m) {
        if (props != null) {
            props.set("outputMode", m.toString());
        } else {
            outModeSet = true;
        }
        outMode = m;
    }

    /**
     * Get input mode.
     * @return Current input mode
     */
    public static InputMode getInputMode() {
        return inMode;
    }

    /**
     * Get source image as BufferedImage.
     * @return Source image as BufferedImage
     */
    public static BufferedImage getSrcImage() {
        synchronized (semaphore) {
            return substream.getImage();
        }
    }

    /**
     * Get source image as BufferedImage.
     * @param idx	Index of subtitle
     * @return		Source image as BufferedImage
     * @throws CoreException
     */
    public static BufferedImage getSrcImage(int idx) throws CoreException {
        synchronized (semaphore) {
            substream.decode(idx);
            return substream.getImage();
        }
    }

    /**
     * Get target image as BufferedImage.
     * @return Target image as BufferedImage
     */
    public static BufferedImage getTrgImage() {
        synchronized (semaphore) {
            return trgBitmap.getImage(trgPal.getColorModel());
        }
    }

    /**
     * Get target image as BufferedImage.
     * @param pic SubPicture to use for applying erase patches
     * @return Target image as BufferedImage
     */
    public static BufferedImage getTrgImagePatched(SubPicture pic) {
        synchronized (semaphore) {
            if (pic.erasePatch != null) {
                Bitmap trgBitmapPatched = new Bitmap(trgBitmapUnpatched);
                int col = trgPal.getIndexOfMostTransparentPaletteEntry();
                for (ErasePatch ep : pic.erasePatch) {
                    trgBitmapPatched.fillRectangularWithColorIndex(ep.x, ep.y, ep.width, ep.height, (byte)col);
                }
                return trgBitmapPatched.getImage(trgPal.getColorModel());
            } else {
                return trgBitmapUnpatched.getImage(trgPal.getColorModel());
            }
        }
    }

    /**
     * Get screen width of target.
     * @param index Subtitle index
     * @return Screen width of target
     */
    public static int getTrgWidth(int index) {
        synchronized (semaphore) {
            return subPictures[index].width;
        }
    }

    /**
     * Get screen height of target.
     * @param index Subtitle index
     * @return Screen height of target
     */
    public static int getTrgHeight(int index) {
        synchronized (semaphore) {
            return subPictures[index].height;
        }
    }

    /**
     * Get subtitle width of target.
     * @param index Subtitle index
     * @return Subtitle width of target
     */
    public static int getTrgImgWidth(int index) {
        synchronized (semaphore) {
            return subPictures[index].getImageWidth();
        }
    }

    /**
     * Get subtitle height of target.
     * @param index Subtitle index
     * @return Subtitle height of target
     */
    public static int getTrgImgHeight(int index) {
        synchronized (semaphore) {
            return subPictures[index].getImageHeight();
        }
    }

    /**
     * Get exclude (from export) state of target.
     * @param index Subtitle index
     * @return Screen width of target
     */
    public static boolean getTrgExcluded(int index) {
        synchronized (semaphore) {
            return subPictures[index].exclude;
        }
    }

    /**
     * Get subtitle x offset of target.
     * @param index Subtitle index
     * @return Subtitle x offset of target
     */
    public static int getTrgOfsX(int index) {
        synchronized (semaphore) {
            return subPictures[index].getOfsX();
        }
    }

    /**
     * Get subtitle y offset of target.
     * @param index Subtitle index
     * @return Subtitle y offset of target
     */
    public static int getTrgOfsY(int index) {
        synchronized (semaphore) {
            return subPictures[index].getOfsY();
        }
    }

    /**
     * Get number of subtitles.
     * @return Number of subtitles
     */
    public static int getNumFrames() {
        return substream == null ? 0 : substream.getNumFrames();
    }

    /**
     * Get number of forced subtitles.
     * @return Number of forced subtitles
     */
    public static int getNumForcedFrames() {
        return substream == null ? 0 : substream.getNumForcedFrames();
    }

    /**
     * Create info string for target subtitle.
     * @param index Index of subtitle
     * @return Info string for target subtitle
     */
    public static String getTrgInfoStr(int index) {
        SubPicture pic = subPictures[index];
        String text = "screen size: "+getTrgWidth(index)+"x"+getTrgHeight(index)+"    ";
        text +=	"image size: "+getTrgImgWidth(index)+"x"+getTrgImgHeight(index)+"    ";
        text += "pos: ("+pic.getOfsX()+","+pic.getOfsY()+") - ("+(pic.getOfsX()+getTrgImgWidth(index))+","+(pic.getOfsY()+getTrgImgHeight(index))+")    ";
        text += "start: "+ptsToTimeStr(pic.startTime)+"    ";
        text += "end: "+ptsToTimeStr(pic.endTime)+"    ";
        text += "forced: "+((pic.isforced)?"yes":"no");
        return text;
    }

    /**
     * Create info string for source subtitle.
     * @param index Index of subtitle
     * @return Info string for source subtitle
     */
    public static String getSrcInfoStr(int index) {
        String text;

        SubPicture pic = substream.getSubPicture(index);
        text  = "screen size: "+pic.width+"x"+pic.height+"    ";
        text +=	"image size: "+pic.getImageWidth()+"x"+pic.getImageHeight()+"    ";
        text += "pos: ("+pic.getOfsX()+","+pic.getOfsY()+") - ("+(pic.getOfsX()+pic.getImageWidth())+","+(pic.getOfsY()+pic.getImageHeight())+")    ";
        text += "start: "+ptsToTimeStr(pic.startTime)+"    ";
        text += "end: "+ptsToTimeStr(pic.endTime)+"    ";
        text += "forced: "+((pic.isforced)?"yes":"no");
        return text;
    }

    /**
     * Get width and height for given resolution.
     * @param r Resolution
     * @return Integer array containing width [0] and height [1]
     */
    public static int[] getResolution(Resolution r) {
        return r.getDimensions();
    }

    /**
     * Get Idx string representation of resolution.
     * @param r Resolution
     * @return String representation of resolution
     */
    public static String getResolutionName(Resolution r) {
        return r.toString();
    }

    /**
     * Get Xml string representation of resolution.
     * @param r Resolution
     * @return String representation of resolution
     */
    public static String getResolutionNameXml(Resolution r) {
        return r.getResolutionNameForXml();
    }


    /**
     * Get current DVD palette.
     * @return DVD palette
     */
    public static Palette getCurrentDVDPalette() {
        return currentDVDPalette;
    }

    /**
     * Set current DVD palette.
     * @param pal DVD palette
     */
    public static void setCurrentDVDPalette(Palette pal) {
        currentDVDPalette = pal;
    }

    /**
     * Get language index for VobSub (and XML) export.
     * @return Language index for VobSub (and XML) export
     */
    public static int getLanguageIdx() {
        return languageIdx;
    }

    /**
     * Set language index for VobSub (and XML) export.
     * @param idx Language index for VobSub (and XML) export
     */
    public static void setLanguageIdx(int idx) {
        languageIdx = idx;
    }

    /**
     * Set flag that tells whether to fix frames shorter than minTimePTS.
     * @return Flag that tells whether to fix frames shorter than minTimePTS
     */
    public static boolean getFixShortFrames() {
        return fixShortFrames;
    }

    /**
     * Set flag that tells whether to fix frames shorter than minTimePTS.
     * @param b True: fix short frames
     */
    public static void setFixShortFrames(boolean b) {
        fixShortFrames = b;
        if (props == null) {
            fixShortFramesSet = true;
        }
    }

    /**
     * Store flag that tells whether to fix frames shorter than minTimePTS.
     * @param b True: fix short frames
     */
    public static void storeFixShortFrames(boolean b) {
        props.set("fixShortFrames",  b);
    }

    /**
     * Get minimum frame duration in 90kHz resolution.
     * @return Minimum frame duration in 90kHz resolution
     */
    public static int getMinTimePTS() {
        return minTimePTS;
    }

    /**
     * Set minimum frame duration in 90kHz resolution.
     * @param t Minimum frame duration in 90kHz resolution
     */
    public static void setMinTimePTS(int t) {
        minTimePTS = t;
        if (props == null) {
            minTimePtsSet = true;
        }
    }

    /**
     * Store minimum frame duration in 90kHz resolution.
     * @param t Minimum frame duration in 90kHz resolution
     */
    public static void storeMinTimePTS(int t) {
        props.set("minTimePTS", t);
    }

    /**
     * Get target subpicture.
     * @param index Index of subpicture
     * @return Target SubPicture
     */
    public static SubPicture getSubPictureTrg(int index) {
        synchronized (semaphore) {
            return subPictures[index];
        }
    }

    /**
     * Get source subpicture.
     * @param index Index of subpicture
     * @return Source SubPicture
     */
    public static SubPicture getSubPictureSrc(int index) {
        synchronized (semaphore) {
            return substream.getSubPicture(index);
        }
    }

    /**
     * Get flag that defines whether to swap Cr/Cb components when loading a SUP.
     * @return True: swap cr/cb
     */
    public static boolean getSwapCrCb() {
        return swapCrCb;
    }

    /**
     * Set flag that defines whether to swap Cr/Cb components when loading a SUP.
     * @param b True: swap cr/cb
     */
    public static void setSwapCrCb(boolean b) {
        swapCrCb = b;
    }

    /**
     * Set Y coordinate cropping offset.
     * @param ofs Cropping Offset (number of lines to crop symmetrically from bottom and top)
     */
    public static void setCropOfsY(int ofs) {
        cropOfsY = ofs;
    }

    /**
     * Get Y coordinate cropping offset.
     * @return Current cropping Offset (number of lines to crop symmetrically from bottom and top)
     */
    public static int getCropOfsY() {
        return cropOfsY;
    }

    /**
     * Get: use of BT.601 color model instead of BT.709.
     * @return True if BT.601 is used
     */
    public static boolean usesBT601() {
        return useBT601;
    }

    /**
     * Get palette creation mode.
     * @return Current palette creation mode
     */
    public static PaletteMode getPaletteMode() {
        return paletteMode;
    }

    /**
     * Set palette creation mode.
     * @param m Palette creation mode
     */
    public static void setPaletteMode(PaletteMode m) {
        if (props != null) {
            props.set("paletteMode", m.toString());
        } else {
            paletteModeSet = true;
        }
        paletteMode = m;
    }

    /**
     * Get verbatim console output mode.
     * @return True: verbatim console output mode
     */
    public static boolean getVerbatim() {
        return verbatim;
    }

    /**
     * Set verbatim console output mode.
     * @param e True: verbatim console output mode
     */
    public static void setVerbatim(boolean e) {
        verbatim = e;
        if (props != null) {
            props.set("verbatim", e ? "true" : "false");
        } else {
            verbatimSet = true;
        }
    }

    /**
     * Set internal maximum for progress bar.
     * @param max Internal maximum for progress bar (e.g. number of subtitles)
     */
    public static void setProgressMax(int max) {
        progressMax = max;
    }

    /**
     * Get: use source fps for target fps if possible.
     * @return True if source fps should be used for target
     */
    public static boolean getKeepFps() {
        return keepFps;
    }

    /**
     * Set: use source fps for target fps if possible.
     * @param e True if source fps should be used for target
     */
    public static void setKeepFps(boolean e) {
        keepFps = e;
    }

    /**
     * Get: source frame rate is certain
     * @return true if source frame rate is certain
     */
    public static boolean getFpsSrcCertain() {
        return fpsSrcCertain;
    }

    /**
     * Set: source frame rate is certain
     * @param c true if source frame rate is certain
     */
    public static void setFpsSrcCertain(boolean c) {
        fpsSrcCertain = c;
    }

    /**
     * Get current scaling filter.
     * @return Current scaling filter
     */
    public static ScalingFilter getScalingFilter() {
        return scalingFilter;
    }

    /**
     * Set filter to be used for scaling.
     * @param f Scaling Filter
     */
    public static void setScalingFilter(ScalingFilter f) {
        if (props != null) {
            props.set("filter", f.toString());
        } else {
            scalingFilterSet = true;
        }
        scalingFilter = f;
    }

    /**
     * Get maximum time difference for merging captions.
     * @return Maximum time difference for merging captions
     */
    public static int getMergePTSdiff() {
        return mergePTSdiff;
    }

    /**
     * Set maximum time difference for merging captions.
     * @param d Maximum time difference for merging captions
     */
    public static void setMergePTSdiff(int d) {
        mergePTSdiff = d;
        if (props != null) {
            props.set("mergePTSdiff", d);
        } else {
            mergePTSdiffSet = true;
        }
    }

    /**
     * Get alpha threshold for cropping.
     * @return Alpha threshold for cropping
     */
    public static int getAlphaCrop() {
        return alphaCrop;
    }

    /**
     * Set alpha threshold for cropping.
     * @param a Alpha threshold for cropping
     */
    public static void setAlphaCrop(int a) {
        alphaCrop = a;
        if (props != null) {
            props.set("alphaCrop", a);
        } else {
            alphaCropSet = true;
        }
    }

    /**
     * Report whether free scaling is active or not.
     * @return true if free scaling is applied
     */
    public static boolean getApplyFreeScale() {
        return applyFreeScale;
    }

    /**
     * Enable/disable free scaling
     * @param f true: free scaling is applied
     */
    public static void setApplyFreeScale(boolean f) {
        applyFreeScale = f;
        if (props == null) {
            applyFreeScaleSet = true;
        }
    }

    /**
     * Store: Enable/disable free scaling
     * @param f true: free scaling is applied
     */
    public static void storeApplyFreeScale(boolean f) {
        props.set("applyFreeScale", f);
    }

    /**
     * Get free scaling factor.
     * @return Free X scaling factor
     */
    public static double getFreeScaleX() {
        return freeScaleX;
    }

    /**
     * Get free scaling factor.
     * @return Free Y scaling factor
     */
    public static double getFreeScaleY() {
        return freeScaleY;
    }

    /**
     * Set free scaling factor.
     * @param x Free X scaling factor (limited to 0.5 .. 2.0)
     * @param y Free Y scaling factor (limited to 0.5 .. 2.0)
     */
    public static void setFreeScale(double x, double y) {
        if (x < MIN_FREE_SCALE_FACTOR) {
            x = MIN_FREE_SCALE_FACTOR;
        } else if (x > MAX_FREE_SCALE_FACTOR) {
            x = MAX_FREE_SCALE_FACTOR;
        }
        freeScaleX = x;
        if (y < MIN_FREE_SCALE_FACTOR) {
            y = MIN_FREE_SCALE_FACTOR;
        } else if (y > MAX_FREE_SCALE_FACTOR) {
            y = MAX_FREE_SCALE_FACTOR;
        }
        freeScaleY = y;
    }

    /**
     * Store free scaling factor.
     * @param x Free X scaling factor (limited to 0.5 .. 2.0)
     * @param y Free Y scaling factor (limited to 0.5 .. 2.0)
     */
    public static void storeFreeScale(double x, double y) {
        if (x < MIN_FREE_SCALE_FACTOR) {
            x = MIN_FREE_SCALE_FACTOR;
        } else if (x > MAX_FREE_SCALE_FACTOR) {
            x = MAX_FREE_SCALE_FACTOR;
        }
        props.set("freeScaleX", x);
        if (y < MIN_FREE_SCALE_FACTOR) {
            y = MIN_FREE_SCALE_FACTOR;
        } else if (y > MAX_FREE_SCALE_FACTOR) {
            y = MAX_FREE_SCALE_FACTOR;
        }
        props.set("freeScaleY", y);
    }

    /**
     * Set: move mode in Y direction
     * @param m Move mode
     */
    public static void setMoveModeY(CaptionMoveModeY m) {
        moveModeY = m;
        moveCaptions = (moveModeY != CaptionMoveModeY.KEEP_POSITION) || (moveModeX != CaptionMoveModeX.KEEP_POSITION);
    }

    /**
     * Get: move mode in Y direction
     * @return Move mode
     */
    public static CaptionMoveModeY getMoveModeY() {
        return moveModeY;
    }

    /**
     * Set: move mode in X direction
     * @param m Move mode
     */
    public static void setMoveModeX(CaptionMoveModeX m) {
        moveModeX = m;
        moveCaptions = (moveModeY != CaptionMoveModeY.KEEP_POSITION) || (moveModeX != CaptionMoveModeX.KEEP_POSITION);
    }

    /**
     * Get: move mode in X direction
     * @return Move mode
     */
    public static CaptionMoveModeX getMoveModeX() {
        return moveModeX;
    }

    /**
     * Set: factor of cinemascope bars (needed for moving after cropping).
     * @param f Factor of cinemascope bars
     */
    public static void setCineBarFactor(double f) {
        cineBarFactor = f;
    }

    /**
     * Set: Additional y offset to consider when moving
     * @param ofs Y offset
     */
    public static void setMoveOffsetY(int ofs) {
        moveOffsetY = ofs;
    }

    /**
     * Get: Additional y offset to consider when moving
     * @return Y offset
     */
    public static int getMoveOffsetY() {
        return moveOffsetY;
    }

    /**
     * Set: Additional x offset to consider when moving
     * @param ofs Y offset
     */
    public static void setMoveOffsetX(int ofs) {
        moveOffsetX = ofs;
    }

    /**
     * Get: Additional x offset to consider when moving
     * @return X offset
     */
    public static int getMoveOffsetX() {
        return moveOffsetX;
    }

    /**
     * Get: keep move settings after loading a new stream
     * @return true: keep settings, false: ignore settings
     */
    public static boolean getMoveCaptions() {
        return moveCaptions;
    }

    /**
     * Set: keep move settings after loading a new stream
     * @param m true: keep settings, false; ignore settings
     */
    public static void setMoveCaptions(boolean m) {
        moveCaptions = m;
    }

    /**
     * Get current input stream ID.
     * @return Stream ID
     */
    public static StreamID getCurrentStreamID() {
        return currentStreamID;
    }

    /**
     * Set current input stream ID.
     * @param sid Stream ID
     */
    public static void setCurrentStreamID(StreamID sid) {
        currentStreamID = sid;
    }

    /**
     * Get: write PGCEdit palette file on export.
     * @return True: write
     */
    public static boolean getWritePGCEditPal() {
        return writePGCEditPal;
    }

    /**
     * Set: write PGCEdit palette file on export.
     * @param e True: write
     */
    public static void setWritePGCEditPal(boolean e) {
        writePGCEditPal = e;
        if (props != null) {
            props.set("writePGCEditPal", e ? "true" : "false");
        } else {
            writePGCEditPalSet = true;
        }
    }

    /**
     * Get: fix completely invisibly subtitles due to alpha=0 (SUB/IDX and SUP/IFO import only).
     * @return True: verbatim text mode
     */
    public static boolean getFixZeroAlpha() {
        return fixZeroAlpha;
    }

    /**
     * Set: fix completely invisibly subtitles due to alpha=0 (SUB/IDX and SUP/IFO import only).
     * @param e True: verbatim text mode
     */
    public static void setFixZeroAlpha(boolean e) {
        fixZeroAlpha = e;
        if (props != null) {
            props.set("fixZeroAlpha", e ? "true" : "false");
        } else {
            fixZeroAlphaSet = true;
        }
    }

    /**
     * Get imported palette if input is DVD format.
     * @return Imported palette if input is DVD format, else null
     */
    public static Palette getDefSrcDVDPalette() {
        return defaultSourceDVDPalette;
    }

    /**
     * Get modified imported palette if input is DVD format.
     * @return Imported palette if input is DVD format, else null
     */
    public static Palette getCurSrcDVDPalette() {
        return currentSourceDVDPalette;
    }

    /**
     * Set modified imported palette.
     * @param pal Modified imported palette
     */
    public static void setCurSrcDVDPalette(Palette pal) {
        currentSourceDVDPalette = pal;

        SubstreamDVD substreamDVD = null;
        if (inMode == InputMode.VOBSUB) {
            substreamDVD = subDVD;
        } else if (inMode == InputMode.SUPIFO) {
            substreamDVD = supDVD;
        }

        substreamDVD.setSrcPalette(currentSourceDVDPalette);
    }

    /**
     * Return frame palette of given subtitle.
     * @param index Index of subtitle
     * @return Frame palette of given subtitle as array of int (4 entries)
     */
    public static int[] getFramePal(int index) {
        SubstreamDVD substreamDVD = null;

        if (inMode == InputMode.VOBSUB) {
            substreamDVD = subDVD;
        } else if (inMode == InputMode.SUPIFO) {
            substreamDVD = supDVD;
        }

        if (substreamDVD != null) {
            return substreamDVD.getFramePal(index);
        } else {
            return null;
        }
    }

    /**
     * Return frame alpha values of given subtitle.
     * @param index Index of subtitle
     * @return Frame alpha values of given subtitle as array of int (4 entries)
     */
    public static int[] getFrameAlpha(int index) {
        SubstreamDVD substreamDVD = null;

        if (inMode == InputMode.VOBSUB) {
            substreamDVD = subDVD;
        } else if (inMode == InputMode.SUPIFO) {
            substreamDVD = supDVD;
        }

        if (substreamDVD != null) {
            return substreamDVD.getFrameAlpha(index);
        } else {
            return null;
        }
    }

    /**
     * Return original frame palette of given subtitle.
     * @param index Index of subtitle
     * @return Frame palette of given subtitle as array of int (4 entries)
     */
    public static int[] getOriginalFramePal(int index) {
        SubstreamDVD substreamDVD = null;

        if (inMode == InputMode.VOBSUB) {
            substreamDVD = subDVD;
        } else if (inMode == InputMode.SUPIFO) {
            substreamDVD = supDVD;
        }

        if (substreamDVD != null) {
            return substreamDVD.getOriginalFramePal(index);
        } else {
            return null;
        }
    }

    /**
     * Return original frame alpha values of given subtitle.
     * @param index Index of subtitle
     * @return Frame alpha values of given subtitle as array of int (4 entries)
     */
    public static int[] getOriginalFrameAlpha(int index) {
        SubstreamDVD substreamDVD = null;

        if (inMode == InputMode.VOBSUB) {
            substreamDVD = subDVD;
        } else if (inMode == InputMode.SUPIFO) {
            substreamDVD = supDVD;
        }

        if (substreamDVD != null) {
            return substreamDVD.getOriginalFrameAlpha(index);
        } else {
            return null;
        }
    }

}