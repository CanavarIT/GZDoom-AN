// FluidSynth replacement for platforms without glib (Android).
// The bundled FluidSynth needs glib, which does not exist there. Creating the device
// fails with the same exception the real code throws, so the engine falls back to
// another synth (OPL, Timidity, WildMidi, ...).

#include <stdexcept>
#include <string>
#include <vector>
#include "zmusic/zmusic_internal.h"
#include "mididevice.h"

FluidConfig fluidConfig;

MIDIDevice *CreateFluidSynthMIDIDevice(int samplerate, const char *Args)
{
	(void)samplerate; (void)Args;
	throw std::runtime_error("FluidSynth is not available in this build.\n");
}
