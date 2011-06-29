#pragma once

#include "ofMain.h"
#include "ofxiPhone.h"
#include "ofxiPhoneExtras.h"
#include "Ball.h"

#include "ofxOsc.h"

#define NUM_POINTS				1
#define HOST "192.168.178.22"
#define PORT 32000


class testApp : public ofxiPhoneApp {
	
public:
	void setup();
	void update();
	void draw();
	void exit();

	void touchDown(int x, int y, int id);
	void touchMoved(int x, int y, int id);
	void touchUp(int x, int y, int id);
	void touchDoubleTap(int x, int y, int id);
	
	void lostFocus();
	void gotFocus();
	void gotMemoryWarning();
	void deviceOrientationChanged(int newOrientation);
    
    void sendDataViaOSC(float r, float g, float b);
    
    ofImage image;
	
	Ball balls[NUM_POINTS];
    
    ofxOscSender oscSender;
    ofxOscBundle oscBundle;
    ofxOscMessage oscMessage;
};
