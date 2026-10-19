# Synthesises the IRIDIS feedback sounds (app/src/main/res/raw/iris_*.ogg).
# python3 scripts/gen_feedback_sounds.py OUTDIR && for f in OUTDIR/*.wav; do ffmpeg -i $f -c:a libvorbis -q:a 3 ${f%.wav}.ogg; done
import numpy as np, wave, sys
SR=44100
def tone(freqs, dur, amp, attack=0.004, decay=None, glide=None):
    n=int(SR*dur); t=np.arange(n)/SR
    sig=np.zeros(n)
    for i,(f,a,start) in enumerate(freqs):
        s=int(start*SR); tt=t[:n-s]
        ff=f if glide is None else f*(1+glide*tt/dur)
        ph=2*np.pi*np.cumsum(np.broadcast_to(ff,tt.shape))/SR
        env=np.minimum(1,tt/attack)*np.exp(-tt/(decay or dur/4))
        sig[s:]+=a*np.sin(ph)*env + a*0.15*np.sin(2*ph)*env
    # fade out last 8 ms
    f=int(0.008*SR); sig[-f:]*=np.linspace(1,0,f)
    sig=sig/np.max(np.abs(sig))*amp
    return (sig*32767).astype(np.int16)
def save(name,data):
    with wave.open(f"{sys.argv[1] if len(sys.argv)>1 else '.'}/{name}.wav","wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(data.tobytes())
save("iris_send",  tone([(880,1,0)], 0.07, 0.30, decay=0.018, glide=0.25))
save("iris_done",  tone([(784,1,0),(1175,0.8,0.05)], 0.14, 0.28, decay=0.03))
save("iris_tick",  tone([(1568,1,0)], 0.035, 0.22, attack=0.001, decay=0.006))
save("iris_error", tone([(330,1,0),(247,0.9,0.05)], 0.13, 0.32, decay=0.03))
