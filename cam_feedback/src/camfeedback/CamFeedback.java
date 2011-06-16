package camfeedback;

//import codeanticode.gsvideo.GSCapture;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import netP5.NetAddress;
import oscP5.OscMessage;
import oscP5.OscP5;
import processing.core.PApplet;
import processing.core.PImage;
import processing.video.Capture;


public class CamFeedback extends PApplet {

    private static boolean START_WITH_EXISTING_IMAGE = false;

    private static final int NUM_COLORS_IN_QUERY = 3;
    private static final int COLOR_BUCKET_RESOLUTION = 12;
    private static final int MIN_COLOR_DISTANCE = 20;
    private static final int MIN_COLOR_DISTANCE_LAST = 80;

    private static final int MIN_SATURATION = 0;
    private static final int MAX_SATURATION = 250;
    private static final int MIN_BRIGHTNESS = 50;

    private static final int COLOR_FUZZINESS_KERNEL_SIZE = 3;


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

            Comparator<ColorBucket> occurenceComparator = new Comparator<CamFeedback.ColorBucket>() {

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
                Comparator<ColorBucket> hueComparator = new Comparator<CamFeedback.ColorBucket>() {

                    public int compare(ColorBucket o1, ColorBucket o2) {
                        float hue1 = hue(o1.color);
                        float hue2 = hue(o2.color);
                        return hue1 < hue2 ? -1 : hue1 > hue2 ? 1 : 0;
                    }
                };

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

	PImage img, screenCap;
	float deg=1;
	int level=0;
	int levelMax=3;

	OscP5 oscP5;

	/* a NetAddress contains the ip address and port number of a remote location in the network. */
	NetAddress myBroadcastLocation; 
	
	Capture cam;
	
	ColorCollector colorCollector;
    private List<ColorBucket> dominantColors;

	
	public void setup() {
		size(500, 500, P3D);
	//  size(850, 850, P3D);
	  frameRate(30);
	  // img=loadImage("merce.png");
	  
	  oscP5 = new OscP5(this,12000);
//	  myBroadcastLocation = new NetAddress("127.0.0.1",32000);
	  
	  myBroadcastLocation = new NetAddress("130.149.141.55",32000);
	  cam = new Capture(this, 320, 240);
	  
	}
	private void drawRecursive(int level)
	{
	  screenCap=get(0, 0, width, height); 

	  tint(255, 255, 255, 200);
	  //rotateX(radians(deg));
	  pushMatrix();
	  translate(width/4, height/4);
	  rotate(radians(deg));
	  translate(-width/4, -height/4);
	  image(screenCap, 0, 0, width/2, height/2);
	  popMatrix();

	  pushMatrix();
	  translate(3*width/4, height/4);
	  rotate(radians(-4*deg));
	  translate(-3*width/4, -height/4);
	  image(screenCap, width/2, 0, width/2, height/2);
	  popMatrix();

	  pushMatrix();
	  translate(width/4, 3*height/4);
	  rotate(radians(deg));
	  translate(-width/4, -3*height/4);
	  image(screenCap, 0, height/2, width/2, height/2);
	  popMatrix();

	  pushMatrix();
	  translate(3*width/4, 3*height/4);
	  rotate(radians(-4*deg));
	  translate(-3*width/4, -3*height/4);
	  image(screenCap, width/2, height/2, width/2, height/2);
	  popMatrix();   

	  if (level>0)
	    drawRecursive(level-1);
	}


	public void draw() {
		background(0);

		  if (cam.available() == true) {
		    cam.read();

		    translate(width/2, height/2);
		    rotate(radians(deg));
		    translate(-width/2, -height/2);
		    // tint(255,255,255,200);
		    // image(img, 0, 0, width, height);
		    image(cam, 0, 0, width, height);

		    sendColors(cam);
		    // scale(levelMax);
//		    drawRecursive(levelMax);

		    mouseX=constrain(mouseX, 0, width);        // values for mouseX between 0 and window width
		    mouseY=constrain(mouseY, 0, height);       // values for mouseY between 0 and window height

		      deg+=(-5.0 + 2* (float)mouseX/(float)width * 5.0);      // degrees -5 or plus 5
		    levelMax=floor(6* (float)mouseY/(float)height);  // values between 0 and
		  }
	}

	public void keyPressed() {
		OscMessage m;
		switch(key) {
		case('c'):
			/* connect to the broadcaster */
			m = new OscMessage("/server/connect",new Object[0]);
		OscP5.flush(m,myBroadcastLocation);  
		break;
		case('d'):
			/* disconnect from the broadcaster */
			m = new OscMessage("/server/disconnect",new Object[0]);
		OscP5.flush(m,myBroadcastLocation);  
		break;

		}  
	}

	void oscEvent(OscMessage theOscMessage) {
		  /* get and print the address pattern and the typetag of the received OscMessage */
		  println("### received an osc message with addrpattern "+theOscMessage.addrPattern()+" and typetag "+theOscMessage.typetag());
		  theOscMessage.print();
	}
	
	void sendColors(PImage img){
		if(mousePressed){
			colorCollector = new ColorCollector(COLOR_BUCKET_RESOLUTION);
			colorCollector.analyze(img);
			dominantColors = colorCollector.getDominantColors(NUM_COLORS_IN_QUERY, false);

			for (int i = 0; i < dominantColors.size(); i++) {
				ColorBucket colBucket = dominantColors.get(i);
				OscMessage myOscMessage = new OscMessage("/color" + i);
				/* add a value (an integer) to the OscMessage */
				myOscMessage.add(red(colBucket.color));
				myOscMessage.add(green(colBucket.color));
				myOscMessage.add(blue(colBucket.color));
				myOscMessage.add(colBucket.occurence);
				oscP5.send(myOscMessage, myBroadcastLocation);
				//			println(colBucket.color);
				//			println(colBucket.occurence);
			}
		}
		
	}
	public static void main(String _args[]) {
//		PApplet.main(new String[] { "--present", camfeedback.CamFeedback.class.getName() });
		PApplet.main(new String[] { camfeedback.CamFeedback.class.getName() });
	}
}
