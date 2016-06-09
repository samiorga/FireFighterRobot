 /*
 * Sam and Catalin
 * May 4, 2016
 * Ver 1.0
 * The main code for the Dacia Firefighter. This program controls the movement of the bot,
 * the sensors (line, candle, sonar), the LCD display, the servo motor, and the water
 * pump.
 */

// include the library code:
#include <LiquidCrystal.h>
#include <Servo.h>

// initialize the library with the numbers of the interface pins
LiquidCrystal lcd(13, 12, 11, 10, 9, 8);
Servo servo;

//Constants
int waterPump = 7;
int candleDetect = A0;
int rightLineDetect = A3;
int leftLineDetect = A4;
int rmCalib = 230;  //analogWrite() value for the right motor to synchronize its speed with left motor

//Variables
int rightLineState = 0;
int leftLineState = 0;
int candleValue[30];
int room = 0; //keep track of what room we're currently in

//true or false variables
boolean inRoom = false;
boolean candleDET = false;

//Set up servo boundaries
int servoMin = 15;  //minimum servo angle facing RIght
int servoMax = 165; //maximum servo angle facing left

//Set up sonar variables
//for calculating distance
int duration;
int distance[10];
int meanDistance = 0;

//ideal distance from wall (i.e. middle of corridor)
int idealDist = 0;

//Enum used for direction processing with the sonar
enum Direction  {
  front,
  left,
  right,
  null  //exception handling (this shouldn't ever happen, however)
};

boolean check;  //used initially to see which way to go; let right be true and left be false

//this variable changes right before the servo rotates
Direction previousDir;  //keep track of where the sonar was last facing

//Time variables
unsigned long timer;
int servoTime = 500; //How long the servo should face any one direction (ms)

void setup() {
  // put your setup code here, to run once:
  
  // set up the LCD's number of columns and rows:
  lcd.begin(16, 2);
  lcd.clear();
  Serial.begin(9600);
  
  //Set up left motor I/O lines to output
  pinMode(2, OUTPUT);
  pinMode(3, OUTPUT);
  //Set up right motor I/O lines to output
  pinMode(4, OUTPUT);
  pinMode(5, OUTPUT);

  //Set up servo motor
  pinMode(6, OUTPUT);
  servo.attach(6);

  //Set up water pump
  pinMode(7, OUTPUT);

  //Set up IR detector
  pinMode(A0, INPUT);

  //Set up line detection
  pinMode(A3, INPUT); //Right
  pinMode(A4, INPUT); //Left

  //Set up sonar
  pinMode(A1, OUTPUT);  //trigger
  pinMode(A2, INPUT);   //echo

  //Face right (hug right wall)
  servo.write(servoMin);
  delay(servoTime*2);
  
  timer = millis(); //initialize timer
}

void loop() {
  // put your main code here, to run repeatedly:

  //find value for idealDist (it is only reset when it equals 0)
  if (idealDist == 0) {
    idealDist = recordDistance();
  }

  if (!inRoom)  {
    wallHugger();
  }
  else  {
    searchRoom();
  }
}

void wallHugger() {
  //debugging purposes
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print("NAVIGATING...");
  
  lineDetect();
  if (recordDistance() < idealDist - 10) {  //moves close to the wall
      leftPivot();
      delay(20);
    }
  else if (recordDistance() > idealDist + 10 && recordDistance() <= 50)  {  //moves away from the wall
      rightPivot();
      delay(20);
    }
  else  {   //in the middle of the corridor
      forward();
    }
  
  //if the wall becomes too far away
  if (recordDistance() > 50)  { //approximate
    idealDist = 0;  //reset variable
    timer = millis();
    //go forward for a bit
    do  {
      forward();
      //space for more code, e.g. line detect
      lineDetect();
      if (rightLineState == HIGH || leftLineState == HIGH)
        break;
    }while(millis() - timer < 1000);
    //pivot right until wall is reached  
    while (recordDistance() >= 20 && (rightLineState == LOW && leftLineState == LOW)){
      rightPivot();
      lineDetect();
    }
  }
}

int recordDistance() {
  //Send ten pulses
  
  for (int i = 0; i < 10; i++)  {
      digitalWrite(A1, LOW);   //don't emit sound
      delayMicroseconds(2);
      digitalWrite(A1, HIGH);  //emit a pulse
      delayMicroseconds(10);
      digitalWrite(A1, LOW);   //pulse has been emitted for 10 us    
      duration = pulseIn(A2, HIGH);  //amount of time it takes for pulse to bounce back (in us)
      distance[i] = (duration/2) / 29.1;  //calculate distance based off duration
      meanDistance += distance[i];
    }

  meanDistance /= 10; //calculate average of the 10 distances (retrieved from pulses)
  /*
  Serial.println(meanDistance); //print to monitor (for debugging)
  lcd.setCursor(0, 0);
  lcd.clear();
  lcd.print(meanDistance, DEC);  //print distance on lcd screen (for debugging)
  */
  return meanDistance;
}

void checkLeft() {
  leftLineState = digitalRead(leftLineDetect);
}

void checkRight() {
  rightLineState = digitalRead(rightLineDetect);
}

void checkBoth() {
  rightLineState = digitalRead(rightLineDetect);
  leftLineState = digitalRead(leftLineDetect);
}

void lineDetect() {       //Line detection and adjustment for robot to enter the room straight
  checkBoth();
  if (rightLineState){                                              //after it detects line it does this part of the code
    while (!leftLineState){
      rightPivot();
      checkLeft();
    }
    inRoom = true;
    room++;
  } else if (leftLineState) {
    while (!rightLineState){
      leftPivot();
      checkRight();
    }
    inRoom = true;
    room++;
  }
}

void searchRoom() {
  if (room == 1)  {
    //debugging purposes
    lcd.clear();
    lcd.setCursor(0, 0);
    lcd.print("SEARCHING ROOM");
    //pivot right until every corner can be scanned
    rightPivot();
    delay(1250);
    arrete();
    //scan the room
    int candlePos = -1;
    for (int i = servoMin; i <= servoMax; i += 5) {
      candlePos++;
      servo.write(i);
      delay(25);
      candleValue[candlePos] = analogRead(candleDetect);
      //if there's a candle detected, break the loop
      //FIX:
      if (candlePos >= 1 && candleValue[candlePos - 1] < 50 && candleValue[candlePos] > candleValue[candlePos - 1] ) {
        servo.write(i - 5);
        delay(25);
        break;
      }
      lcd.clear();
      lcd.setCursor(0, 1);
      lcd.print(candleValue[candlePos], DEC);
    }

    if (candleValue[candlePos - 1] > 50)  { //if there's no flame
      exitRoom();
    }
    else  {
      extinguish();
    }
  }
  else if (room == 2) {
    
  }
  else if (room == 3) {
    
  }
  else if (room == 4) {
    
  }
}

void exitRoom() {
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print("EXITING ROOM");
  delay(10000000);
}

void extinguish()  {
    //squirt water for 1.2 s
    digitalWrite(waterPump, HIGH);
    delay(500);
}

void arrete() {   //Don't want to override stop() function
  //Stop left motor
  digitalWrite(2, LOW);
  digitalWrite(3, LOW);
  
  //Stop right motor
  digitalWrite(4, LOW);
  digitalWrite(5, LOW);
}

void backward()  {
  //Turn right motor backwards
  analogWrite(2, rmCalib);
  digitalWrite(3, LOW);

  //Turn left motor backwards
  digitalWrite(4, LOW);
  digitalWrite(5, HIGH);
}

void forward()  {
  //Turn right motor forwards
  digitalWrite(2, LOW);
  analogWrite(3, rmCalib);

  //Turn left motor forwards
  digitalWrite(4, HIGH);
  digitalWrite(5, LOW);
}

void rightSpin() {
  //Turn right motor backwards
  analogWrite(2, rmCalib);
  digitalWrite(3, LOW);

  //Turn left motor forewards
  digitalWrite(4, HIGH);
  digitalWrite(5, LOW);
}

void leftSpin() {
  //Turn right motor forwards
  digitalWrite(2, LOW);
  analogWrite(3, rmCalib);

  //Turn left motor backwards
  digitalWrite(4, LOW);
  digitalWrite(5, HIGH);
}

void rightPivot() {
  //Stop right motor
  digitalWrite(2, LOW);
  digitalWrite(3, LOW);

  //Turn left motor forwards
  digitalWrite(4, HIGH);
  digitalWrite(5, LOW);
}

void leftPivot() {
  //Turn right motor forwards
  digitalWrite(2, LOW);
  analogWrite(3, rmCalib);

  //Stop left motor
  digitalWrite(4, LOW);
  digitalWrite(5, LOW);
}
