# nrsc5-chopper

Split up the output from HD radio into discreet files, 
using an RTL-SDR Blog V3 USB dongle.


## Setup

* A raspberry pi ([splurge for the best](https://www.raspberrypi.com/products/raspberry-pi-4-model-b/?variant=raspberry-pi-4-model-b-8gb) and install the [64 bit OS](https://www.raspberrypi.com/software/operating-systems/#raspberry-pi-os-64-bit))
* a Digital FM to USB RTL-SDR dongle (I used [this model](https://smile.amazon.com/gp/product/B011HVUEME))
* `ffmpeg` with aac ([install steps](src/main/resources/build_ffmpeg.sh))
* `nrsc5` ([compiled from here](https://github.com/theori-io/nrsc5))
* OpenJDK and Kotlin  (`sudo apt install openjdk-11-jdk kotlin`) and Gradle (`sudo snap install gradle --classic`)
  * (but maybe IntelliJ instead?) 
* This project (`git clone https://github.com/salamanders/nrsc5-chopper`)
* Lots of drive space

TODO

- [x] Bouncing though a WAV file fills up eventually.  We should write to "-" instead
- [x] Earlier start times (the moment the artist or title isn't right)
- [x] Real command line args
- [x] Pass in station on the command line 
- [x] Pick the best image
- [ ] m4a encoding using ffmpeg from [this installer](https://gist.github.com/chemputer/5fa4319bc0882cad6613f21f051bbcd2) - holding off until we can trim to song start.
- [ ] Metadata should have a shorter TTL - if you stop seeing Title, you are in a commercial break.
- [ ] embed images
- [ ] Scan all stations 
- [ ] get the timing right (maybe [silence?](https://ffmpeg.org/ffmpeg-filters.html#silencedetect)) or maybe FFT? https://github.com/mileshenrichs/QuiFFT
- [ ] direct copy from AAC instead of bouncing through the WAV using... uh... magic.
- [ ] Max song length, maybe from a DB? (like wikipedia?)