#include "testApp.h"

//--------------------------------------------------------------
void testApp::setup(){	
	// register touch events
	ofxRegisterMultitouch(this);
	
	// initialize the accelerometer
	ofxAccelerometer.setup();
	
	//iPhoneAlerts will be sent to this.
	ofxiPhoneAlerts.addListener(this);
	
    
	// initialize all of the Ball particles
	for(int i=0; i<NUM_POINTS; i++) balls[i].init();
   
    oscSender.setup(HOST, PORT);    
    image.loadImage("bg.png");
    
   // printf("galllo %i" , image.);
}


//--------------------------------------------------------------
void testApp::update() {
    unsigned char* pixels = image.getPixels();
    
	for(int i=0; i<NUM_POINTS; i++)
    {   
        balls[i].update();  
        
        float r = pixels[(balls[i].getY() * ofGetWidth() + balls[i].getX())*3+0];  
        float g = pixels[(balls[i].getY() * ofGetWidth() + balls[i].getX())*3+1];  
        float b = pixels[(balls[i].getY() * ofGetWidth() + balls[i].getX())*3+2]; 
        
        printf("R: %f G: %f B: %f  \n" ,r ,g ,b );
     
        sendDataViaOSC(r,g,b);
    }
    
    //printf("x = %f   y = %f \n", ofxAccelerometer.getForce().x, ofxAccelerometer.getForce().y);
    

}

//--------------------------------------------------------------
void testApp::draw() {
    ofSetColor(255,255,255);
    image.draw(0,0);
   

	for(int i=0; i<NUM_POINTS; i++) balls[i].draw();
}

//--------------------------------------------------------------
void testApp::exit() {
	printf("exit()\n");
}

//--------------------------------------------------------------
void testApp::touchDown(int x, int y, int id){
	printf("touch %i down at (%i,%i)\n", id, x,y);
	balls[id].moveTo(x, y);
    
 
    
}

//--------------------------------------------------------------
void testApp::touchMoved(int x, int y, int id){
	printf("touch %i moved at (%i,%i)\n", id, x, y);
	balls[id].moveTo(x, y);
}

//--------------------------------------------------------------
void testApp::touchUp(int x, int y, int id){
	printf("touch %i up at (%i,%i)\n", id, x, y);
}

//--------------------------------------------------------------
void testApp::touchDoubleTap(int x, int y, int id){
	printf("touch %i double tap at (%i,%i)\n", id, x, y);
}

//--------------------------------------------------------------
void testApp::lostFocus() {
}

//--------------------------------------------------------------
void testApp::gotFocus() {
}

//--------------------------------------------------------------
void testApp::gotMemoryWarning() {
}

//--------------------------------------------------------------
void testApp::deviceOrientationChanged(int newOrientation){
}

void testApp::sendDataViaOSC(float r, float g, float b)
{
    oscMessage.setAddress("/color1");
    
    
    oscMessage.addFloatArg(r);
    oscMessage.addFloatArg(g);
    oscMessage.addFloatArg(b);
    //oscBundle.addMessage(oscMessage);
    
    oscSender.sendMessage(oscMessage);
    
    oscMessage.clear();
    //oscBundle.clear();
}