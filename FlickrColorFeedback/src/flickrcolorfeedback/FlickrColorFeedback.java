package flickrcolorfeedback;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.xml.parsers.ParserConfigurationException;

import netP5.NetAddress;
import netP5.NetAddressList;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import oscP5.OscMessage;
import oscP5.OscP5;
import processing.core.PApplet;
import processing.core.PFont;
import processing.core.PImage;

import com.aetrion.flickr.Flickr;
import com.aetrion.flickr.REST;
import com.aetrion.flickr.photos.GeoData;
import com.aetrion.flickr.photos.Photo;
import com.aetrion.flickr.photos.PhotosInterface;
import com.aetrion.flickr.photos.Size;
import com.aetrion.flickr.tags.Tag;

public class FlickrColorFeedback extends PApplet {

    private static boolean exportMovie = false;
    private static boolean animateImageBounds = false;

    // 7 most similar colors + 1 that stands out
    // private static boolean START_WITH_EXISTING_IMAGE = true;
    //
    // private static final int NUM_COLORS_IN_QUERY = 8;
    // private static final int COLOR_BUCKET_RESOLUTION = 12;
    // private static final int MIN_COLOR_DISTANCE = 20;
    // private static final int MIN_COLOR_DISTANCE_LAST = 80;
    //
    // private static final int MIN_SATURATION = 0;
    // private static final int MAX_SATURATION = 250;
    // private static final int MIN_BRIGHTNESS = 50;
    //
    // private static final int COLOR_FUZZINESS_KERNEL_SIZE = 3;

    // 4 different colors
    private static boolean START_WITH_EXISTING_IMAGE = false;
    private static final int NUM_COLORS_IN_QUERY = 4;
    private static final int COLOR_BUCKET_RESOLUTION = 15;
    private static final int MIN_COLOR_DISTANCE = 10;
    private static final int MIN_COLOR_DISTANCE_LAST = 100;

    private static final int MIN_SATURATION = 0;
    private static final int MAX_SATURATION = 240;
    private static final int MIN_BRIGHTNESS = 80;

    private static final int COLOR_FUZZINESS_KERNEL_SIZE = 3;

    // no restrictions
    // private static boolean START_WITH_EXISTING_IMAGE = false;
    // private static final int NUM_COLORS_IN_QUERY = 8;
    // private static final int COLOR_BUCKET_RESOLUTION = 10;
    // private static final int MIN_COLOR_DISTANCE = 0;
    // private static final int MIN_COLOR_DISTANCE_LAST = 0;
    //
    // private static final int MIN_SATURATION = 0;
    // private static final int MAX_SATURATION = 255;
    // private static final int MIN_BRIGHTNESS = 0;
    //
    // private static final int COLOR_FUZZINESS_KERNEL_SIZE = 5;

    class ColorBucket {
        public ColorBucket(int color) {
            this.color = color;
        }

        int color;
        float occurence;
    }

    static class Kernel3D {

        Kernel3D(int size) {
            if (size % 2 != 1)
                throw new RuntimeException("kernel size must be an odd number");

            factors = new Float[size][size][size];

            final int center = size / 2;
            final float maxDistance = getDistance(0, 0, 0, center, center, center);
            RgbVisitor.forAllColors(factors, new RgbVisitor() {

                @Override
                void handleColor(int r, int g, int b) {
                    float distance = getDistance(r, g, b, center, center, center);

                    float kernelValue = 1.f - (distance / maxDistance);
                    factors[r][g][b] = kernelValue;
                }
            });
        }

        void forEachKernelValue(final RgbVisitor visitor) {

            final int offset = factors.length / 2;

            RgbVisitor.forAllColors(factors, new RgbVisitor() {

                @Override
                void handleColor(int r, int g, int b) {
                    visitor.handleColor(r - offset, g - offset, b - offset);
                }
            });
        }

        float getFactor(int rOffset, int gOffset, int bOffset) {
            final int offset = factors.length / 2;
            return factors[rOffset + offset][gOffset + offset][bOffset + offset];
        }

        static float getDistance(int r1, int g1, int b1, int r2, int g2, int b2) {
            int r = Math.abs(r1 - r2);
            int g = Math.abs(g1 - g2);
            int b = Math.abs(b1 - b2);

            return (float) Math.pow(r * r * r + g * g * g + b * b * b, 1 / 3.0);
        }

        Float[][][] factors;
    }

    public abstract static class RgbVisitor {

        abstract void handleColor(int r, int g, int b);

        static <T> void forAllColors(T[][][] matrix, RgbVisitor rgbIterator) {
            for (int r = 0; r < matrix.length; r++) {
                for (int g = 0; g < matrix[r].length; g++) {
                    for (int b = 0; b < matrix[r][g].length; b++) {
                        rgbIterator.handleColor(r, g, b);
                    }
                }
            }
        }
    }

    private final class HueComparator implements Comparator<FlickrColorFeedback.ColorBucket> {
        @Override
        public int compare(ColorBucket o1, ColorBucket o2) {
            float hue1 = hue(o1.color);
            float hue2 = hue(o2.color);
            return hue1 < hue2 ? -1 : hue1 > hue2 ? 1 : 0;
        }
    }

    class ColorCollector {

        public ColorCollector(final int resolution) {
            this.colorBuckets = new ColorBucket[resolution][resolution][resolution];

            RgbVisitor.forAllColors(this.colorBuckets, new RgbVisitor() {

                @Override
                void handleColor(int r, int g, int b) {
                    int red = (int) ((r + 0.5) * 256 / resolution);
                    int green = (int) ((g + 0.5) * 256 / resolution);
                    int blue = (int) ((b + 0.5) * 256 / resolution);

                    int bucketColor = color(red, green, blue);
                    colorBuckets[r][g][b] = new ColorBucket(bucketColor);
                }
            });

            this.kernel = new Kernel3D(COLOR_FUZZINESS_KERNEL_SIZE);
        }

        void analyze(PImage image) {

            if (COLOR_FUZZINESS_KERNEL_SIZE > 1) {
                collectColorsBlurred(image);
            } else {
                collectColorsFast(image);
            }
        }

        private void collectColorsBlurred(final PImage image) {
            for (int i = 0; i < image.pixels.length; i++) {
                final int pixelColor = image.pixels[i];

                final int r = getBucketCoord(red(pixelColor));
                final int g = getBucketCoord(green(pixelColor));
                final int b = getBucketCoord(blue(pixelColor));

                this.kernel.forEachKernelValue(new RgbVisitor() {

                    @Override
                    void handleColor(int rOffset, int gOffset, int bOffset) {
                        float factor = kernel.getFactor(rOffset, gOffset, bOffset);

                        int rIndex = r + rOffset;
                        int gIndex = g + gOffset;
                        int bIndex = b + bOffset;

                        if (rIndex >= 0 && rIndex < colorBuckets.length) {
                            if (gIndex >= 0 & gIndex < colorBuckets[r].length) {
                                if (bIndex >= 0 && bIndex < colorBuckets[r][g].length) {
                                    colorBuckets[r][g][b].occurence += factor;
                                }
                            }
                        }
                    }
                });
            }
        }

        private void collectColorsFast(PImage image) {
            for (int i = 0; i < image.pixels.length; i++) {
                int r = getBucketCoord(red(image.pixels[i]));
                int g = getBucketCoord(green(image.pixels[i]));
                int b = getBucketCoord(blue(image.pixels[i]));

                colorBuckets[r][g][b].occurence += 1.f;
            }
        }

        List<ColorBucket> getDominantColors(int numColors, boolean orderByHue) {

            Comparator<ColorBucket> occurenceComparator = new Comparator<FlickrColorFeedback.ColorBucket>() {

                @Override
                public int compare(ColorBucket o1, ColorBucket o2) {
                    return o1.occurence > o2.occurence ? -1 : o1.occurence < o2.occurence ? 1 : 0;
                }
            };

            final SortedSet<ColorBucket> sortedColours = new TreeSet<ColorBucket>(occurenceComparator);
            RgbVisitor.forAllColors(colorBuckets, new RgbVisitor() {

                @Override
                void handleColor(int r, int g, int b) {
                    sortedColours.add(colorBuckets[r][g][b]);
                }
            });

            ArrayList<ColorBucket> dominantColours = new ArrayList<ColorBucket>();

            Iterator<ColorBucket> colorIt = sortedColours.iterator();
            while (colorIt.hasNext()) {
                ColorBucket nextBucket = colorIt.next();

                int minColorDistance = (dominantColours.size() == numColors - 1) ? MIN_COLOR_DISTANCE_LAST
                        : MIN_COLOR_DISTANCE;

                if (calculateDistance(nextBucket.color, dominantColours) > minColorDistance
                        && saturation(nextBucket.color) > MIN_SATURATION
                        && saturation(nextBucket.color) < MAX_SATURATION
                        && brightness(nextBucket.color) > MIN_BRIGHTNESS) {
                    dominantColours.add(nextBucket);
                }
                if (dominantColours.size() == numColors) {
                    break;
                }
            }

            while (dominantColours.size() < numColors) {
                System.out.println("not enough colors, filling with unused color");
                ColorBucket lastColor = sortedColours.last();
                dominantColours.add(lastColor);
                sortedColours.remove(lastColor);
            }

            if (orderByHue) {
                Comparator<ColorBucket> hueComparator = new HueComparator();

                Collections.sort(dominantColours, hueComparator);
            }

            return dominantColours;
        }

        private float calculateDistance(int color, List<ColorBucket> selectedColors) {
            float minDistance = 1000;
            for (int i = 0; i < selectedColors.size(); i++) {
                int otherColor = selectedColors.get(i).color;

                float dist1, dist2, dist3;
                if (useHsvDistance) {
                    dist1 = abs(hue(color) - hue(otherColor));
                    dist2 = abs(saturation(color) - saturation(otherColor));
                    dist3 = abs(brightness(color) - brightness(otherColor));
                } else {
                    dist1 = abs(red(color) - red(otherColor));
                    dist2 = abs(green(color) - green(otherColor));
                    dist3 = abs(blue(color) - blue(otherColor));
                }

                float distance;
                if (useManhattanDistance) {
                    distance = dist1 + dist2 + dist3;
                } else {
                    // calculate euklidian distance
                    float distsTimesThreeSum = dist1 * dist1 * dist1 + dist2 * dist2 * dist2 + dist3 * dist3 * dist3;
                    distance = (float) Math.pow(distsTimesThreeSum, 1 / 3.0);
                }
                if (distance < minDistance) {
                    minDistance = distance;
                }
            }
            return minDistance;
        }

        private int getBucketCoord(float colorValue) {
            return (int) (colorValue / 256 * getResolution());
        }

        public int getResolution() {
            return this.colorBuckets.length;
        }

        boolean useManhattanDistance = false;
        boolean useHsvDistance = true;

        private ColorBucket[][][] colorBuckets;
        Kernel3D kernel;
    }

    class FlickrColorSearch {
        public FlickrColorSearch() {

        }

        public List<String> findImages(Collection<ColorBucket> colors) throws ParseException {

            List<String> idList = new ArrayList<String>();

            String weightString = StringUtils.substring(Float.toString(1.f / colors.size()), 3);

            StringBuilder colorCodes = new StringBuilder();
            int colorIndex = 0;
            Iterator<ColorBucket> it = colors.iterator();
            while (it.hasNext()) {
                colorCodes.append("&colors[" + colorIndex + "]=");
                String hexValue = hex(it.next().color).substring(2);
                colorCodes.append(hexValue);
                colorCodes.append("&weights[" + colorIndex + "]=" + weightString);
                colorIndex++;
            }

            try {
                URL url = new URL(baseUrl + colorCodes);

                InputStream is = url.openStream();
                StringWriter jsonString = new StringWriter();
                IOUtils.copy(is, jsonString);

                JSONParser parser = new JSONParser();
                JSONObject response = (JSONObject) parser.parse(jsonString.toString());

                JSONArray results = (JSONArray) response.get("result");

                System.out.println("found " + results.size() + " similar images");
                for (int i = 0; i < results.size(); i++) {
                    JSONObject result = (JSONObject) results.get(i);
                    idList.add((String) result.get("id"));
                }

                return idList;

            } catch (MalformedURLException e) {
                e.printStackTrace();
                return idList;
            } catch (IOException e) {
                e.printStackTrace();
                return idList;
            }

        }

        private String baseUrl = "http://labs.ideeinc.com/rest/?method=flickr_color_search&limit=51&offset=0";
    }

    class FlickrPhotoInfo {

        FlickrPhotoInfo(String photoId, File localFile) {
            file = localFile;
            id = photoId;

            tags = new TreeSet<String>();
        }

        FlickrPhotoInfo(Photo photo, File localFile) {
            this(photo.getId(), localFile);

            Iterator<Tag> tagIterator = photo.getTags().iterator();
            while (tagIterator.hasNext()) {
                tags.add(tagIterator.next().getValue().toLowerCase());
            }

            dateTaken = photo.getDateTaken();

            if (photo.hasGeoData()) {
                GeoData geoData = photo.getGeoData();
                geoLatitude = geoData.getLatitude();
                geoLongitude = geoData.getLongitude();
                geoAccuracy = geoData.getAccuracy();
            }
        }

        String getId() {
            return id;
        }

        boolean hasMetadata() {
            return hasTags() || hasDate() || hasLocation();
        }

        boolean hasTags() {
            return !tags.isEmpty();
        }

        boolean hasDate() {
            return dateTaken != null;
        }

        boolean hasLocation() {
            return geoLatitude > 0;
        }

        File getFile() {
            return file;
        }

        Date getDate() {
            return dateTaken;
        }

        String getLocation() {
            return geoLongitude + "/" + geoLatitude;
        }

        String getTags() {
            return StringUtils.join(tags, " ");
        }

        File file;

        String id;
        SortedSet<String> tags;
        Date dateTaken;

        float geoLatitude;
        float geoLongitude;
        int geoAccuracy;
    }

    class FlickrDownloader {

        private final static String apiKey = "75c5346836583de9642d969fbde321db";
        private final static String apiSecret = "2f73b4492701f043";

        public FlickrDownloader() throws ParserConfigurationException {
            Flickr flickr = new Flickr(apiKey, apiSecret, new REST());
            photos = flickr.getPhotosInterface();
        }

        public FlickrPhotoInfo downloadPhoto(String id) {

            try {
                File imageFile = new File(imageDataDir, id + ".jpg");

                if (imageFile.exists()) {
                    System.out.println("photo " + id + " has already been downloaded.");
                    return new FlickrPhotoInfo(id, imageFile);
                }

                Photo photo = photos.getPhoto(id);
                FlickrPhotoInfo info = new FlickrPhotoInfo(photo, imageFile);

                if (photo.isPublicFlag()) {
                    InputStream is = photos.getImageAsStream(photo, Size.MEDIUM);
                    if (is != null) {
                        FileOutputStream fos = new FileOutputStream(imageFile);
                        IOUtils.copy(is, fos);

                        System.out.println("Sucessfully downloaded " + info.getId());
                        return info;

                    } else {
                        System.out.println("Couldn't open inputstream for photo " + id);
                    }
                }

            } catch (Exception e) {
                System.out.println("Failed to get photo " + id);
                e.printStackTrace();
            }

            unavailablePhotoIds.add(id);
            return null;
        }

        private PhotosInterface photos;

    }

    class ImageReplacingThread extends Thread {
        ImageReplacingThread() {
            newImageAvailable = false;
        }

        public void run() {
            while (!threadShouldExit) {
                try {
                    newPhotoInfo = downloadSimilarImage();
                    if (newPhotoInfo != null) {
                        newImageAvailable = true;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    threadShouldExit = true;
                }
            }
        }

        private FlickrPhotoInfo downloadSimilarImage() throws ParseException {

            List<String> idList = flickrColorSearch.findImages(searchColours);
            FlickrPhotoInfo info = null;
            for (int i = 0; i < idList.size(); i++) {
                String id = idList.get(i);
                if (!unavailablePhotoIds.contains(id) && !shownPhotoIds.contains(id)) {
                    info = flickrDownloader.downloadPhoto(idList.get(i));
                    if (info != null) {
                        shownPhotoIds.add(id);
                        loadNextImage(info.getId());
                        return info;
                    }
                }
            }
            System.out.println("no more matching images found");
            return null;
        }

        boolean isNewImageAvailable() {
            return newImageAvailable;
        }

        PImage getIncomingImage() {
            newImageAvailable = false;
            return incomingImage;
        }

        private void loadNextImage(String id) {

            // Load an image from the data directory
            incomingImage = loadImage(imageDataDir.getName() + "/" + id + ".jpg");

            collector = new ColorCollector(COLOR_BUCKET_RESOLUTION);
            collector.analyze(incomingImage);
            dominantColors = collector.getDominantColors(NUM_COLORS_IN_QUERY, true);

            updateSearchColors();
            sendColors();
        }

        PImage incomingImage;

        boolean threadShouldExit;
        Boolean newImageAvailable;

        FlickrPhotoInfo newPhotoInfo;
    }

    public void setup() {

        size(1280, 720, P2D);

        System.out.println("FlickColorFeedback.setup() was called.");
        oscp5 = new OscP5(this, myListeningPort);

        peerAddresses = new LinkedList<NetAddress>();
        peerAddresses.add( new NetAddress("130.149.141.58", 12000));
        peerAddresses.add( new NetAddress("130.149.141.51", 57120));

        coloursFromOsc = new ArrayList<ColorBucket>();

        imageDataDir = new File(dataPath("images"));
        cacheDataDir = new File(dataPath("cache"));

        flickrColorSearch = new FlickrColorSearch();

        try {
            flickrDownloader = new FlickrDownloader();
        } catch (ParserConfigurationException e) {
            e.printStackTrace();
        }

        shownPhotoIds = new ArrayList<String>();
        unavailablePhotoIds = new ArrayList<String>();

        if (START_WITH_EXISTING_IMAGE) {
            // star with the first image in the data directory
            imgFileNames = imageDataDir.list(new FilenameFilter() {

                @Override
                public boolean accept(File dir, String name) {
                    String extension = name.substring(name.lastIndexOf(".") + 1).toLowerCase();

                    return extension.equals("jpg") || extension.equals("jpeg") || extension.equals("gif")
                            || extension.equals("png");
                }
            });

            imageReplacingThread.loadNextImage(StringUtils.substringBefore(imgFileNames[0], "."));
        } else {
            // start with random colors
            dominantColors = new ArrayList<ColorBucket>();
            for (int i = 0; i < NUM_COLORS_IN_QUERY; i++) {
                dominantColors.add(new ColorBucket(color(random(200), random(200), random(200))));
            }

            currentImage = createImage(640, 480, RGB);
        }
        updateImageLocation();

        hint(ENABLE_NATIVE_FONTS);
        font = createFont("Arial", 20, false);

        dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.ENGLISH);

        int monitorWidth;
        int monitorHeight;
        GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice devices[] = environment.getScreenDevices();
        // System.out.println(Arrays.toString(devices));

        if (devices.length > 1) { // we have a 2nd display/projector
            // learn the true dimensions of the secondary display
            monitorWidth = devices[1].getDisplayMode().getWidth();
            monitorHeight = devices[1].getDisplayMode().getHeight();
        } else { // no 2nd screen but make it fullscreen anyway
            monitorWidth = devices[0].getDisplayMode().getWidth();
            monitorHeight = devices[0].getDisplayMode().getHeight();
        }
        // size(monitorWidth, monitorHeight);

        // frame.setSize(monitorWidth, monitorHeight);

        imageReplacingThread = new ImageReplacingThread();
        updateSearchColors();
        imageReplacingThread.start();
    }

    private void updateSearchColors() {

        synchronized (this) {
            searchColours = new ArrayList<ColorBucket>();

            int numColorsFromImage = min(4, 5 - coloursFromOsc.size());

            for (int i = 0; i < numColorsFromImage; i++) {
                searchColours.add(dominantColors.get(i));
            }
            for (int i = 0; i < coloursFromOsc.size(); i++) {
                searchColours.add(coloursFromOsc.get(i));
            }

            HueComparator hueComparator = new HueComparator();
            Collections.sort(searchColours, hueComparator);
        }
    }

    public void draw() {

        background(0.f);

        // draw dominant colors

        noStroke();
        rectMode(CORNER);

        if (animateImageBounds) {
            float horizCenter = width / 2.f;
            float vertCener = height / 2.f;

            float fadeFactor = 0.999f;
            imgLeft = fadeFactor * imgLeft + (1.f - fadeFactor) * horizCenter;
            imgRight = fadeFactor * imgRight + (1.f - fadeFactor) * horizCenter;
            imgTop = fadeFactor * imgTop + (1.f - fadeFactor) * vertCener;
            imgBottom = fadeFactor * imgBottom + (1.f - fadeFactor) * vertCener;
        }

        List<ColorBucket> coloursToDisplay = new ArrayList<ColorBucket>();
        for (int i = 0; i < dominantColors.size(); i++) {
            if (!coloursFromOsc.isEmpty()) {
                coloursToDisplay.add(coloursFromOsc.get(i % coloursFromOsc.size()));
            } else {
                coloursToDisplay.add(new ColorBucket(0));
            }
            coloursToDisplay.add(dominantColors.get(i));
        }

        if (coloursToDisplay != null && coloursToDisplay.size() == 4) {

            fill(coloursToDisplay.get(0).color);
            rect(imgLeft, 0, imgRight - imgLeft, imgTop);

            fill(coloursToDisplay.get(1).color);
            rect(imgRight, imgTop, width - imgRight, imgBottom - imgTop);

            fill(coloursToDisplay.get(2).color);
            rect(imgLeft, imgBottom, imgRight - imgLeft, height - imgBottom);

            fill(coloursToDisplay.get(3).color);
            rect(0, imgTop, imgLeft, imgBottom - imgTop);

        } else if (coloursToDisplay != null && coloursToDisplay.size() == 8) {

            fill(coloursToDisplay.get(0).color);
            rect(0, 0, imgLeft, imgTop);

            fill(coloursToDisplay.get(1).color);
            rect(imgLeft, 0, imgRight - imgLeft, imgTop);

            fill(coloursToDisplay.get(2).color);
            rect(imgRight, 0, width - imgRight, imgTop);

            fill(coloursToDisplay.get(3).color);
            rect(imgRight, imgTop, width - imgRight, imgBottom - imgTop);

            fill(coloursToDisplay.get(4).color);
            rect(imgRight, imgBottom, width - imgRight, height - imgBottom);

            fill(coloursToDisplay.get(5).color);
            rect(imgLeft, imgBottom, imgRight - imgLeft, height - imgBottom);

            fill(coloursToDisplay.get(6).color);
            rect(0, imgBottom, imgLeft, height - imgBottom);

            fill(coloursToDisplay.get(7).color);
            rect(0, imgTop, imgLeft, imgBottom - imgTop);

        } else if (coloursToDisplay != null) {

            float rectWidth = width / (float) coloursToDisplay.size();
            float rectHeight = height / 10.0f;
            for (int i = 0; i < coloursToDisplay.size(); i++) {
                int color = coloursToDisplay.get(i).color;
                fill(color);
                rect(i * rectWidth, height - rectHeight, rectWidth, rectHeight);
            }
        }

        if (currentImage != null) {
            // draw image
            imageMode(CORNERS);
            image(currentImage, imgLeft, imgTop, imgRight, imgBottom);
        }

        // draw optional border
        int border = 3;
        if (border > 0) {
            fill(color(255.f));
            rectMode(CORNERS);
            rect(imgLeft, imgTop - 1, imgRight, imgTop + border - 1);
            rect(imgLeft - 1, imgTop, imgLeft + border - 1, imgBottom);
            rect(imgLeft + 1, imgBottom - border + 1, imgRight + 1, imgBottom + 1);
            rect(imgRight - border + 1, imgTop, imgRight + 1, imgBottom);
        }

        if (currentPhoto != null && currentPhoto.hasMetadata()) {
             displayMetadata(imgLeft, imgTop, imgRight, imgBottom);
        }

        if (imageReplacingThread.isNewImageAvailable()) {
            currentImage = imageReplacingThread.getIncomingImage();
            updateImageLocation();
        }

        if (exportMovie) {
            saveFrame("screen-" + nf(frameCount, 4) + ".png");
        }
    }

    private void updateImageLocation() {
        imgLeft = round((width - currentImage.width) * (0.25f + random(0.5f)));
        imgTop = round((height - currentImage.height) * (0.25f + random(0.5f)));
        imgRight = imgLeft + currentImage.width;
        imgBottom = imgTop + currentImage.height;
    }

    private void displayMetadata(float photoLeft, float photoTop, float photoRight, float photoBottom) {
        loadPixels();

        textFont(font);

        if (currentPhoto.hasDate()) {
            textAlign(RIGHT, BOTTOM);
            float x = photoRight - 3;
            float y = photoTop - 3;
            int localBackground = pixels[(int) (y) * width + (int) x];
            fill(brightness(localBackground) < 128 ? 255.f : 0.f);
            text(dateFormat.format(currentPhoto.getDate()), x, y);
        }

        if (currentPhoto.hasLocation()) {
            textAlign(LEFT, TOP);
            float x = photoRight + 3;
            float y = photoTop + 3;
            int localBackground = pixels[(int) (y) * width + (int) x];
            fill(brightness(localBackground) < 128 ? 255.f : 0.f);
            text(currentPhoto.getLocation(), x, y);
        }

        if (currentPhoto.hasTags()) {
            textAlign(LEFT, TOP);
            float x = photoLeft + 3;
            float y = photoBottom + 3;
            int localBackground = pixels[(int) (y) * width + (int) x];
            fill(brightness(localBackground) < 128 ? 255.f : 0.f);
            text(currentPhoto.getTags(), x, y, photoRight - x, height - y);
        }
    }

    public void keyPressed() {

    }

    void sendColors() {
        if (true) {
            for (int i = 0; i < dominantColors.size(); i++) {
                ColorBucket colBucket = dominantColors.get(i);
                OscMessage myOscMessage = new OscMessage("/color" + i);
                /* add a value (an integer) to the OscMessage */
                myOscMessage.add(red(colBucket.color));
                myOscMessage.add(green(colBucket.color));
                myOscMessage.add(blue(colBucket.color));
                myOscMessage.add(colBucket.occurence);
                for (NetAddress peerAddress : peerAddresses)
                {
                	oscp5.send(myOscMessage, peerAddress);
                }
                // println(colBucket.color);
                // println(colBucket.occurence);
            }
        }
    }

    void oscEvent(OscMessage theOscMessage) {

        theOscMessage.print();
        /* check if the address pattern fits any of our patterns */
        if (theOscMessage.addrPattern().equals(myConnectPattern)) {
            connect(theOscMessage.netAddress().address());
        } else if (theOscMessage.addrPattern().equals(myDisconnectPattern)) {
            disconnect(theOscMessage.netAddress().address());
        }
        /**
         * if pattern matching was not successful, then broadcast the incoming
         * message to all addresses in the netAddresList.
         */
        else {
            int colorIndex = NumberUtils.toInt(StringUtils.substringAfter(theOscMessage.addrPattern(), "/color"));

            // make sure not to receive more colors than used in the uery
            if (colorIndex < NUM_COLORS_IN_QUERY) {
            	float red = theOscMessage.get(0).floatValue();
                float green = theOscMessage.get(1).floatValue();
                float blue = theOscMessage.get(2).floatValue();

                int color = color(red, green, blue);

                // make sure there are enough color buckets available to store
                // the incoming color
                while (coloursFromOsc.size() <= colorIndex) {
                    coloursFromOsc.add(new ColorBucket(0));
                }

                coloursFromOsc.set(colorIndex, new ColorBucket(color));
            }
        }

        updateSearchColors();
    }

    private void connect(String theIPaddress) {
        if (!myNetAddressList.contains(theIPaddress, peerListeningPort)) {
            myNetAddressList.add(new NetAddress(theIPaddress, peerListeningPort));
            println("### adding " + theIPaddress + " to the list.");
        } else {
            println("### " + theIPaddress + " is already connected.");
        }
        println("### currently there are " + myNetAddressList.list().size() + " remote locations connected.");
    }

    private void disconnect(String theIPaddress) {
        if (myNetAddressList.contains(theIPaddress, peerListeningPort)) {
            myNetAddressList.remove(theIPaddress, peerListeningPort);
            println("### removing " + theIPaddress + " from the list.");
        } else {
            println("### " + theIPaddress + " is not connected.");
        }
        println("### currently there are " + myNetAddressList.list().size());
    }

    public static void main(String args[]) {
        PApplet.main(new String[] { "--present", "flickrcolorfeedback.FlickrColorFeedback" });
    }

    private ImageReplacingThread imageReplacingThread;

    private FlickrPhotoInfo currentPhoto;
    private PImage currentImage;
    private PFont font;

    private DateFormat dateFormat;

    private ColorCollector collector;

    private String[] imgFileNames;

    private List<ColorBucket> dominantColors;

    private List<String> shownPhotoIds;
    private List<String> unavailablePhotoIds;

    private FlickrColorSearch flickrColorSearch;
    private FlickrDownloader flickrDownloader;
    private File dataDir;
    private File imageDataDir;
    private File cacheDataDir;

    private List<ColorBucket> coloursFromOsc;

    private OscP5 oscp5;
    NetAddressList myNetAddressList = new NetAddressList();
    /*
     * a NetAddress contains the ip address and port number of a remote location
     * in the network.
     */
    List<NetAddress> peerAddresses;

    /* listeningPort is the port the server is listening for incoming messages */
    int myListeningPort = 32000;
    /*
     * the broadcast port is the port the clients should listen for incoming
     * messages from the server
     */
    int peerListeningPort = 12000;

    String myConnectPattern = "/server/connect";
    String myDisconnectPattern = "/server/disconnect";

    private List<ColorBucket> searchColours;

    private float imgLeft;

    private float imgTop;

    private float imgRight;

    private float imgBottom;
}
