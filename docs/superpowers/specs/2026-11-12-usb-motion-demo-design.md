# USB motion demo

First milestone approved in chat: connect the Waveshare ESP32-S3-Touch-AMOLED-2.06 to the Mac, establish recovery, and run a six-axis motion demo. Later milestones are Android BLE streaming and independent SD-card recording for badminton.

Use the official Arduino board examples and their compatible dependencies. Keep tooling local to this repository. Back up the entire existing flash before uploading. Verify the backup's length and checksum and document restoration. Do not alter security fuses or erase the entire flash.

The demo will stream timestamped accelerometer and gyroscope readings over native USB and show a simple status on the watch. USB capture on the Mac will save CSV with explicit units, reject malformed samples, and report timestamp/sequence gaps. This milestone does not claim swing recognition, onboard persistence, Android support, or calibrated sport metrics.

Approaches: USB is selected for the first bench test because the watch is already connected. BLE/Android adds a second software system and is the next stage. SD provides independent sports recording and is deferred until a card is available.

Acceptance: firmware compiles, recovery artifacts exist before upload, and a bounded capture from the actual watch contains finite readings from all six axes with increasing sample timestamps. Human motion validation requires tilting the watch; stationary readings alone are not sport validation.
