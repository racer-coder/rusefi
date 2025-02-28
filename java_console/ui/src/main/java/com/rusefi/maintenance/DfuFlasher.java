package com.rusefi.maintenance;

import com.opensr5.ini.IniFileModel;
import com.rusefi.ConsoleUI;
import com.rusefi.Launcher;
import com.rusefi.Timeouts;
import com.rusefi.autodetect.PortDetector;
import com.rusefi.autodetect.SerialAutoChecker;
import com.rusefi.io.DfuHelper;
import com.rusefi.io.IoStream;
import com.rusefi.io.serial.SerialIoStreamJSerialComm;
import com.rusefi.ui.StatusWindow;

import javax.swing.*;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static com.rusefi.StartupFrame.appendBundleName;

/**
 * @see FirmwareFlasher
 */
public class DfuFlasher {
    private static final String DFU_BINARY_LOCATION = Launcher.TOOLS_PATH + File.separator + "STM32_Programmer_CLI/bin";
    private static final String DFU_BINARY = "STM32_Programmer_CLI.exe";

    public static void doAutoDfu(Object selectedItem, JComponent parent) {
        if (selectedItem == null) {
            JOptionPane.showMessageDialog(parent, "Failed to located serial ports");
            return;
        }
        String port = selectedItem.toString();
        StringBuilder messages = new StringBuilder();

        AtomicBoolean isSignatureValidated = new AtomicBoolean(true);
        if (!PortDetector.isAutoPort(port)) {
            messages.append("Using selected " + port + "\n");
            IoStream stream = SerialIoStreamJSerialComm.openPort(port);
            AtomicReference<String> signature = new AtomicReference<>();
            new SerialAutoChecker(port, new CountDownLatch(1)).checkResponse(stream, new Function<SerialAutoChecker.CallbackContext, Void>() {
                @Override
                public Void apply(SerialAutoChecker.CallbackContext callbackContext) {
                    signature.set(callbackContext.getSignature());
                    return null;
                }
            });
            if (signature.get() == null) {
                JOptionPane.showMessageDialog(ConsoleUI.getFrame(), "rusEFI has not responded on selected " + port + "\n" +
                        "Maybe try automatic serial port detection?");
                return;
            }
            boolean isSignatureValidatedLocal = DfuHelper.sendDfuRebootCommand(parent, signature.get(), stream, messages);
            isSignatureValidated.set(isSignatureValidatedLocal);
        } else {
            messages.append("Auto-detecting port...\n");
            // instead of opening the just-detected port we execute the command using the same stream we used to discover port
            // it's more reliable this way
            port = PortDetector.autoDetectSerial(callbackContext -> {
                boolean isSignatureValidatedLocal = DfuHelper.sendDfuRebootCommand(parent, callbackContext.getSignature(), callbackContext.getStream(), messages);
                isSignatureValidated.set(isSignatureValidatedLocal);
                return null;
            }).getSerialPort();
            if (port == null) {
                JOptionPane.showMessageDialog(ConsoleUI.getFrame(), "rusEFI serial port not detected");
                return;
            } else {
                messages.append("Detected rusEFI on " + port + "\n");
            }
        }
        StatusWindow wnd = new StatusWindow();
        wnd.showFrame(appendBundleName("DFU status " + Launcher.CONSOLE_VERSION));
        wnd.appendMsg(messages.toString());
        if (isSignatureValidated.get()) {
            if (!ProgramSelector.IS_WIN) {
                wnd.appendMsg("Switched to DFU mode!");
                wnd.appendMsg("rusEFI console can only program on Windows");
                return;
            }
            ExecHelper.submitAction(() -> {
                timeForDfuSwitch(wnd);
                executeDFU(wnd);
            }, DfuFlasher.class + " thread");
        } else {
            wnd.appendMsg("Please use manual DFU to change bundle type.");
        }
    }

    public static void runDfuProgramming() {
        StatusWindow wnd = new StatusWindow();
        wnd.showFrame(appendBundleName("DFU status " + Launcher.CONSOLE_VERSION));
        ExecHelper.submitAction(() -> executeDFU(wnd), DfuFlasher.class + " thread");
    }

    private static void executeDFU(StatusWindow wnd) {
        StringBuffer stdout = new StringBuffer();
        String errorResponse = ExecHelper.executeCommand(DFU_BINARY_LOCATION,
                getDfuCommand(),
                DFU_BINARY, wnd, stdout);
        if (stdout.toString().contains("Download verified successfully")) {
            // looks like sometimes we are not catching the last line of the response? 'Upgrade' happens before 'Verify'
            wnd.appendMsg("SUCCESS!");
            wnd.appendMsg("Please power cycle device to exit DFU mode");
        } else if (stdout.toString().contains("Target device not found")) {
            wnd.appendMsg("ERROR: Device not connected or STM32 Bootloader driver not installed?");
            wnd.appendMsg("ERROR: Please try installing drivers using 'Install Drivers' button on rusEFI splash screen");
            wnd.appendMsg("ERROR: Alternatively please install drivers using Device Manager pointing at 'drivers/silent_st_drivers/DFU_Driver' folder");
            wnd.setErrorState(true);
        } else {
            wnd.appendMsg(stdout.length() + " / " + errorResponse.length());
            wnd.appendMsg("ERROR: does not look like DFU has worked!");
            wnd.setErrorState(true);
        }
    }

    private static void timeForDfuSwitch(StatusWindow wnd) {
        wnd.appendMsg("Giving time for USB enumeration...");
        try {
            Thread.sleep(2 * Timeouts.SECOND);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String getDfuCommand() {
        String fileName = IniFileModel.findFile(Launcher.INPUT_FILES_PATH, "rusefi", ".hex");
        if (fileName == null)
            return "File not found";
        String absolutePath = new File(fileName).getAbsolutePath();

        return DFU_BINARY_LOCATION + "/" + DFU_BINARY + " -c port=usb1 -w " + absolutePath + " -v -s";
    }
}
