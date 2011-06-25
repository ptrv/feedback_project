PitchDetection{
	
	var lastFreqValue, lastAmpValue, ampThreshold;
	var window, pitchDetButton, uView, startAngle;
	var pitchdetection, pdSynth, server, oscNode, oscFeedBackNode, mainColor, feedbackColor, oscOutput, oscSendLabel, bootRoutine;
	var freqBuffer, freqBufferPointer, freqBufferSize, ampBuffer, ampBufferPointer, ampBufferSize;
	var g, j,v,i;
	
	*new{
		|ipAddr="130.149.141.55", port=32000 |
		^super.new.init(ipAddr, port);
		
	}	
		
	init{
		|ipAddr, port|
		"INIT".postln;
		
		freqBufferSize = 100;
		freqBuffer = List.newClear(freqBufferSize);
		freqBufferPointer = 0;
		
		ampBufferSize = 30;
		ampBuffer = List.newClear(ampBufferSize);
		ampBufferPointer = 0;
		
		startAngle = 0;
		
		server = Server.local;
		
		//-26dB Threshold
		ampThreshold = 0.1;
		lastAmpValue = 0;
		
		this.sendSynth2Server();
	
		oscOutput = NetAddr(ipAddr, port.asInteger);
	
		g = SCMenuGroup(nil, "PITCH DETECTION", 10);
		j = SCMenuItem(g, "Start PitchDetection");
		j.action = {this.startTracking()};
		i = SCMenuItem(g, "Stop PitchDetection");
		i.action = {this.stopTracking()};
		v = SCMenuItem(g, "new GUI");
		v.action = {this.gui()};

			
		this.gui();
	}
	
	addFreqVal2FreqBuff {
		|val|
		
		freqBuffer.put(freqBufferPointer,val);
		freqBufferPointer = (freqBufferPointer + 1) % freqBufferSize ;
		
						
	}
	
	addAmpVal2AmpBuff {
		|val|
		
		ampBuffer.put(ampBufferPointer,val);
		ampBufferPointer = (ampBufferPointer + 1) % ampBufferSize ;
	}
	
	sendSynth2Server{
		pdSynth = SynthDef("pitchdetection",{
			arg gain = 1, pollFreq = 25;
			var in, amp, freq, hasFreq, out;
			in = SoundIn.ar(0);
			amp = Amplitude.kr(in, 0.05, 0.05);
			# freq, hasFreq = Pitch.kr(in, ampThreshold: 0.03, median: 7);
			SendTrig.kr(Impulse.kr(pollFreq), 0, amp);
			SendTrig.kr(Impulse.kr(pollFreq), 1, freq);
			//Out.ar(0,in * gain)
		}).send(server);						
	}
	
	
	startTracking {
		"Start Tracking".postln;
		
		
		oscNode = OSCresponderNode(server.addr, '/tr', {arg time, resp, msg;
			
			msg[2].asInteger.switch(
				0,{
					lastAmpValue = msg[3].asFloat;
										
					this.addAmpVal2AmpBuff(msg[3].asFloat);
					
					if( lastAmpValue > ampThreshold ,
						{
							this.sendOSCMessage("/gain" , this.calcMeanOfArray(ampBuffer));
						},{}
					);
						
				},
				1,{ 
					if( lastAmpValue > ampThreshold ,
						{
							//var c = Color.hsv( this.freq2ToneNormalized(this.freq2ToneNormalized(this.calcMeanOfArray(freqBuffer))) , 1,1,1,1);
									
							
							mainColor = Color.hsv(startAngle , 1,1,1,1); // +  (this.freq2ToneNormalized(this.calcMeanOfArray(freqBuffer))*0.5 ) ,  1 ,1 ,1 , 1 );
							
							mainColor.postln;
							
							this.addFreqVal2FreqBuff(msg[3].asInteger);
							
							oscOutput.sendMsg( "/color3", mainColor.red*255.0, mainColor.green*255.0, mainColor.blue*255.0);
							
							
											
						},{
							//"amp below Threshold".postln;	
						}
					)
				}
			);
			this.drawUV();
		}).add;
			
			
			
			
		oscFeedBackNode= OSCresponderNode(nil, '/color0', {arg time, resp, msg;
			
			feedbackColor = Color.new(msg[1].asFloat/255,msg[2].asFloat/255,msg[3].asFloat/255);
			startAngle = feedbackColor.asHSV[0];
			["-->" ,  startAngle].postln;
			
		}).add;
				
		pdSynth.play();
		
	}

	stopTracking{
		
		pitchdetection.free;
		oscNode.remove;
		
	}
	
	// OSC Methodes 
	
	sendOSCMessage{
		| label, value |	
		
		//[label, value].postln;
		oscOutput.sendMsg(label.asString(), value.asString());
		
	}	
	
	modifyOSCSettings{
		|ipAddr, port, oscLabel|
		
		oscSendLabel = oscLabel;
		
		oscOutput.disconnect;
		oscOutput = NetAddr(ipAddr.asString(), port.asInteger());
		
	}
	
	// Getter/Setter Methoder
	
	setAmpThreshold{
		|val|
		
		ampThreshold = val;
	}
	
	// GUI Methodes
	
	gui{
		
		window = Window.new("PitchDetection", Rect(Window.screenBounds.width/2 - 100, Window.screenBounds.height/2 - 100, 200, 200));
		window.front;
		window.view.background_(Color.hsv( 0,1,1,1,1));
		uView = UserView(window, Rect(0,0, window.bounds.width, window.bounds.height));
		
	}
	
	
	drawUV{
		
		var ampMeanVal =  this.calcMeanOfArray(ampBuffer);
		var freqMeanVal =  this.freq2ToneNormalized(this.calcMeanOfArray(freqBuffer));		//var c = Color.hsv( this.freq2ToneNormalized(freqMeanVal) , 1,1,1,1);
		 
		defer{
			if(lastAmpValue > ampThreshold ,
				{
					window.view.background_(mainColor,ampMeanVal,1,1,1);
				},
				{}
			);
			
			
			uView.drawFunc={|uview|
				
				Pen.addArc((window.bounds.width/2)@(window.bounds.height/2), (window.bounds.width/2 - 15 ),-1/2*pi, 2pi*ampMeanVal);
				Pen.stroke;
				Pen.addArc((window.bounds.width/2)@(window.bounds.height/2), (window.bounds.width/2 - 5 ), -1/2*pi, -2pi*freqMeanVal);
				Pen.stroke;
				
			};
			window.refresh();
	
		};
		
	}
	
	
	// Help Methodes
	
	calcMeanOfArray{
		|array|
		var sum = 0, mean = 0, count = 0;
		
		array.size.do{
			|i|
			if(
				array[i] != nil,
				{ 
					sum = sum + array[i];
					count = count + 1;
				}		
			);
					
		};
		
		mean = sum/count;
		^mean;		
	}
	
	freq2ToneNormalized{
		|freqVal|
		
		var tone = (cpsmidi(freqVal) % 12) + 1;
		^tone/12;
		
	}	


}