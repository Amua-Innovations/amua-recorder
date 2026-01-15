import asyncio
import struct
import wave
from scipy.io import wavfile
from scipy import signal
import pandas as pd
from bleak import BleakClient, BleakScanner
import plotly.express as px
from plotly.subplots import make_subplots
import plotly.graph_objects as go
import numpy as np
from datetime import datetime as dt
import logging
from logging.handlers import RotatingFileHandler
import os

AUDIO_CHAR_UUID = "12345678-1234-5678-1234-56789abcdef7"
SAMPLE_RATE = 32000 
DEVICES = ["D5:91:CC:7A:AC:E4", "E4:02:D2:DA:A5:29"]


def init_logger():
    logger = logging.getLogger()
    logger.handlers.clear()
    logger.setLevel(logging.INFO)

    file_handler = RotatingFileHandler(filename=os.path.join(
        "logs", "record.log"), maxBytes=1024*1024*10, backupCount=5)
    file_handler.setLevel(logging.INFO)
    file_formatter = logging.Formatter(
        "[%(name)s] %(asctime)s - %(levelname)s: %(message)s")
    file_handler.setFormatter(file_formatter)
    file_handler.doRollover()
    logger.addHandler(file_handler)

    return logger


def log_hexdump_inf(data, logger, label="Audio Packet"):
    """Dumps bytearray in Zephyr LOG_HEXDUMP_INF format using logger.info."""
    line_width = 16
    # Indent matches the typical Zephyr prefix length for sub-lines
    indent = " " * 40 
    
    # Start the string with the label
    lines = [f"{label}: "]
    
    for i in range(0, len(data), line_width):
        chunk = data[i : i + line_width]
        
        # Split into two 8-byte halves for the middle double-space
        left_half = chunk[:8]
        right_half = chunk[8:]
        
        hex_left = " ".join(f"{b:02x}" for b in left_half)
        hex_right = " ".join(f"{b:02x}" for b in right_half)
        
        # Handle formatting for the last line
        if len(chunk) <= 8:
            hex_str = f"{hex_left:<23} " 
        else:
            hex_str = f"{hex_left}  {hex_right}"
            
        # Pad to 49 chars to keep the '|' aligned
        lines.append(f"{indent}{hex_str:<49} |")

    # Combine into a single message with newlines
    full_message = "\n".join(lines)
    logger.info(full_message)


def notification_handler(sender, data, state):

    logger = logging.getLogger(__name__)
    if (dt.now() - state['start_time']).total_seconds() < 2:
        logger.info("Skipping notification, not enough time elapsed")
        return
    
    log_hexdump_inf(data, logger)

    # Parse packet
    seq_num = struct.unpack('<H', data[0:2])[0]

    # Extract 121 samples (int16)
    samples = struct.unpack('<121h', data[1:243])
    state['audio_samples'].extend(samples)
    if state['is_recording']:
        state['recording'].extend(samples)


async def start_stream(client, state):
    logger = logging.getLogger(__name__)
    try:
        # Start notifications
        await client.start_notify(AUDIO_CHAR_UUID, lambda sender, data: notification_handler(sender, data, state))

        # Send START command (0x01)
        await client.write_gatt_char(AUDIO_CHAR_UUID, bytes([0x01]))

        state['start_time'] = dt.now()

        logger.info("Stream started")
        return True
    except Exception as e:
        logger.info(f"Error starting streaming: {e}")
        return False
    

async def stop_stream(client, is_streaming, start_time):
    if not is_streaming:
        return False
    
    logger = logging.getLogger(__name__)
    # Send STOP command (0x00)
    await client.write_gatt_char(AUDIO_CHAR_UUID, bytes([0x00]))

    # Stop notifications
    await client.stop_notify(AUDIO_CHAR_UUID) 

    logger.info(f"Stream stopped, recording time: {dt.now() - start_time}")

    # logger.info(f"Stream stopped, recorded {len(audio_samples)} samples")
    return False


async def start_recording(state):
    logger = logging.getLogger(__name__)
    state['recording'] = []
    logger.info("Starting recording, recording list reset")
    return True


async def stop_recording(recording):
    logger = logging.getLogger(__name__)
    logger.info(f"Stopped recording, {len(recording)} samples recorded")
    return False


async def save_recording(filename, recording):
    with wave.open(f'{filename}', 'wb') as wav_file:
        wav_file.setnchannels(1)  # Mono
        wav_file.setsampwidth(2)  # 16-bit = 2 bytes
        wav_file.setframerate(SAMPLE_RATE)
        wav_file.writeframes(struct.pack(
            f'<{len(recording)}h', *recording))


async def main():
    # Initialise state
    state = {
        'audio_samples': [],
        'recording': [],
        'is_recording': False,
        'is_streaming': False,
        'start_time': None,
    }

    logger = init_logger()

    # Scan for device
    logger.info("Scanning for devices...")

    device = None
    found_event = asyncio.Event()

    def detection_callback(d, advertisement_data):
        nonlocal device
        if d is not None and d.name and "Amua" in d.name and d.address in DEVICES:
            device = d
            logger.info(f"Found device: {d.name} at {d.address}")
            found_event.set()

    scanner = BleakScanner(detection_callback=detection_callback)
    await scanner.start()

    try:
        # Wait for device to be found or timeout after 10 seconds
        await asyncio.wait_for(found_event.wait(), timeout=10.0)
    except asyncio.TimeoutError:
        logger.info("Scan timeout - device not found")
    finally:
        await scanner.stop()
        # Give the scanner time to fully release resources
        await asyncio.sleep(1.0)

    if device is None:
        logger.info("Device not found")
        return

    # Attempt connection
    logger.info("Attempting connection...")
    async with BleakClient(device.address, timeout=30.0) as client:
        logger.info(f"Connected successfully!")

        await asyncio.sleep(0.25)

        services = client.services
        for service in services:
            logger.info(f"Service: {service.uuid}")
            for characteristic in service.characteristics:
                logger.info(f"  Characteristic: {characteristic.uuid}, Properties: {characteristic.properties}")

        await asyncio.sleep(0.25)

        while (1):
            # command = input("Enter command (start_stream/stop_stream/start_record/stop_record/save_record/exit): ")

            command = await asyncio.to_thread(input, "Enter command (start_stream/stop_stream/start_record/stop_record/save_record/exit): ")

            if command == "start_stream" and not state['is_streaming']:
                state['is_streaming'] = await start_stream(client, state)
            if command == "stop_stream" and state['is_streaming']:
                state['is_streaming'] = await stop_stream(client, state['is_streaming'], state['start_time'])
            elif command == "stop_record" and state['is_recording']:
                state['is_recording'] = await stop_recording(state['recording'])
            if command == "start_record" and not state['is_recording']:
                state['is_recording'] = await start_recording(state)
            if command == "save_record":
                filename = input("Enter filename: ")
                await save_recording(filename, state['recording'])
            if command == "exit":
                state['is_streaming'] = await stop_stream(client, state['is_streaming'], state['start_time'])
                break

    if len(state['audio_samples']) == 0:
        logger.info("No audio samples recorded")
        return
    
    # Save as WAV file
    await save_recording('recorded_audio.wav', state['audio_samples'])
        
    sampling_rate, audio_data = wavfile.read('recorded_audio.wav')

    # Time domain data
    time = np.arange(len(audio_data)) / sampling_rate

    # Frequency domain data (Welch's method)
    welch_freq, Pxx = signal.welch(audio_data, fs=sampling_rate, nperseg=2048)

    # Spectrogram
    f, t, Sxx = signal.spectrogram(audio_data, sampling_rate, nperseg=1024)
    Sxx_db = 10 * np.log10(Sxx + 1e-10)  # Convert to dB scale

    # Create subplots
    fig = make_subplots(
        rows=3, cols=1,
        subplot_titles=("Audio Waveform", "Power Spectral Density (Welch)", "Spectrogram"),
        vertical_spacing=0.08,
        specs=[[{"type": "scatter"}], [{"type": "scatter"}], [{"type": "heatmap"}]]
    )

    # Add waveform
    fig.add_trace(
        go.Scatter(x=time, y=audio_data, mode='lines', name='Waveform'),
        row=1, col=1
    )

    # Add Welch PSD
    fig.add_trace(
        go.Scatter(x=welch_freq, y=Pxx, mode='lines', name='Welch PSD'),
        row=2, col=1
    )

    # Add spectrogram
    fig.add_trace(
        go.Heatmap(x=t, y=f, z=Sxx_db, colorscale='Viridis', name='Spectrogram'),
        row=3, col=1
    )

    # Update axes labels
    fig.update_xaxes(title_text="Time (s)", row=1, col=1)
    fig.update_yaxes(title_text="Amplitude", row=1, col=1)
    fig.update_xaxes(title_text="Frequency (Hz)", row=2, col=1)
    fig.update_yaxes(title_text="Power Spectral Density (V²/Hz)", row=2, col=1)
    fig.update_xaxes(title_text="Time (s)", row=3, col=1)
    fig.update_yaxes(title_text="Frequency (Hz)", row=3, col=1)

    fig.update_layout(height=1200, showlegend=False)
    fig.show()

    logger.info(f"Saved {len(state['audio_samples'])} samples to recorded_audio.wav")

asyncio.run(main())
