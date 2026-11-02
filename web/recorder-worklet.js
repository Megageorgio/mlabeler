class MLabelerRecorder extends AudioWorkletProcessor {
  process(inputs) {
    const channels = inputs[0];
    if (channels?.length) {
      const mono = new Float32Array(channels[0].length);
      for (const c of channels) for (let i = 0; i < mono.length; i++) mono[i] += c[i] / channels.length;
      this.port.postMessage(mono, [mono.buffer]);
    }
    return true;
  }
}
registerProcessor('mlabeler-recorder', MLabelerRecorder);
