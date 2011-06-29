#include "ofMain.h"
#include "testApp.h"
#include "ofxOsc.h"


int main(){
	ofSetupOpenGL(1024,768, OF_FULLSCREEN);			// <-------- setup the GL context
	ofRunApp(new testApp);
    

    
}
