using System;
using System.Text.Json;
using System.Threading.Tasks;
using Windows.Devices.WiFiDirect;
using Windows.Security.Credentials;
using Windows.Networking.Sockets;
using Windows.Devices.Enumeration;
using System.Linq;
using System.Collections.Generic;

namespace FastDropBridge
{
    class Program
    {
        static WiFiDirectAdvertisementPublisher? publisher;
        static WiFiDirectConnectionListener? listener;
        static DeviceWatcher? watcher;
        static List<DeviceInformation> discoveredDevices = new List<DeviceInformation>();

        static async Task Main(string[] args)
        {
            if (args.Length > 0 && args[0] == "spike")
            {
                await SpikeStandard();
                return;
            }
            if (args.Length > 0 && args[0] == "spike_autonomous")
            {
                await SpikeAutonomous();
                return;
            }
            if (args.Length > 0 && args[0] == "spike_watcher")
            {
                await SpikeWatcher();
                return;
            }

            Console.WriteLine("Usage: FastDropBridge.exe spike | spike_autonomous | spike_watcher");
            Console.WriteLine("spike: Windows advertises standard P2P");
            Console.WriteLine("spike_autonomous: Windows acts as Legacy AP");
            Console.WriteLine("spike_watcher: Windows actively discovers Android devices");
        }

        static async Task SpikeWatcher()
        {
            Console.WriteLine("Starting Wi-Fi Direct Device Watcher...");
            string deviceSelector = WiFiDirectDevice.GetDeviceSelector(WiFiDirectDeviceSelectorType.AssociationEndpoint);
            watcher = DeviceInformation.CreateWatcher(deviceSelector);

            watcher.Added += (DeviceWatcher sender, DeviceInformation args) =>
            {
                lock (discoveredDevices)
                {
                    discoveredDevices.Add(args);
                    Console.WriteLine($"[{discoveredDevices.Count - 1}] Added: {args.Name} (ID: {args.Id})");
                }
            };

            watcher.Updated += (DeviceWatcher sender, DeviceInformationUpdate args) =>
            {
                Console.WriteLine($"[Watcher] Updated: {args.Id}");
            };

            watcher.Removed += (DeviceWatcher sender, DeviceInformationUpdate args) =>
            {
                Console.WriteLine($"[Watcher] Removed: {args.Id}");
            };

            watcher.EnumerationCompleted += (DeviceWatcher sender, object args) =>
            {
                Console.WriteLine("[Watcher] Enumeration Completed.");
            };

            watcher.Stopped += (DeviceWatcher sender, object args) =>
            {
                Console.WriteLine("[Watcher] Stopped.");
            };

            watcher.Start();
            Console.WriteLine("Scanning with AssociationEndpoint... Ensure Android is running FastDrop.");
            Console.WriteLine("Type the index of the device to connect, or 'q' to quit:");

            while (true)
            {
                var input = Console.ReadLine();
                if (input == "q") break;

                if (int.TryParse(input, out int index))
                {
                    DeviceInformation? selectedDevice = null;
                    lock (discoveredDevices)
                    {
                        if (index >= 0 && index < discoveredDevices.Count)
                        {
                            selectedDevice = discoveredDevices[index];
                        }
                    }

                    if (selectedDevice != null)
                    {
                        Console.WriteLine($"Initiating connection to {selectedDevice.Name}...");
                        try
                        {
                            var wfdDevice = await WiFiDirectDevice.FromIdAsync(selectedDevice.Id);
                            var endpoints = wfdDevice.GetConnectionEndpointPairs();
                            Console.WriteLine("Connected!");
                            foreach (var ep in endpoints)
                            {
                                Console.WriteLine($"  Local: {ep.LocalHostName?.DisplayName}  Remote: {ep.RemoteHostName?.DisplayName}");
                            }

                            // We don't know who is GO. Let's ask user.
                            Console.WriteLine("Type 'L' to act as TCP Server (Listen), or 'C' to act as TCP Client (Connect):");
                            var role = Console.ReadLine()?.ToUpper();
                            if (role == "L")
                            {
                                await TestTcpServer();
                            }
                            else if (role == "C")
                            {
                                var remoteHost = endpoints.FirstOrDefault()?.RemoteHostName?.DisplayName;
                                if (remoteHost != null)
                                {
                                    await TestTcpClient(remoteHost);
                                }
                                else
                                {
                                    Console.WriteLine("No remote host IP found.");
                                }
                            }
                        }
                        catch (Exception ex)
                        {
                            Console.WriteLine("Connection error: " + ex.Message);
                        }
                    }
                    else
                    {
                        Console.WriteLine("Invalid index.");
                    }
                }
            }

            watcher.Stop();
        }

        static async Task SpikeStandard()
        {
            Console.WriteLine("Starting Standard Wi-Fi Direct Advertisement...");
            publisher = new WiFiDirectAdvertisementPublisher();
            publisher.Advertisement.IsAutonomousGroupOwnerEnabled = false;

            publisher.StatusChanged += (s, e) =>
            {
                Console.WriteLine($"Publisher status: {e.Status}");
            };

            listener = new WiFiDirectConnectionListener();
            listener.ConnectionRequested += async (s, e) =>
            {
                Console.WriteLine("Connection requested by: " + e.GetConnectionRequest().DeviceInformation.Name);
                try
                {
                    var wfdDevice = await WiFiDirectDevice.FromIdAsync(e.GetConnectionRequest().DeviceInformation.Id);
                    var endpoints = wfdDevice.GetConnectionEndpointPairs();
                    Console.WriteLine("Connected!");
                    foreach (var ep in endpoints)
                    {
                        Console.WriteLine($"  Local: {ep.LocalHostName?.DisplayName}  Remote: {ep.RemoteHostName?.DisplayName}");
                    }

                    await TestTcpServer();
                }
                catch (Exception ex)
                {
                    Console.WriteLine("Connection error: " + ex.Message);
                }
            };

            publisher.Start();
            Console.WriteLine("Advertising in Standard Mode.");
            Console.WriteLine("Try discovering from Android. Press ENTER to stop.");
            Console.ReadLine();
            publisher.Stop();
        }

        static async Task SpikeAutonomous()
        {
            Console.WriteLine("Starting Autonomous Group Owner (Legacy AP)...");
            publisher = new WiFiDirectAdvertisementPublisher();
            publisher.Advertisement.IsAutonomousGroupOwnerEnabled = true;
            
            publisher.Advertisement.LegacySettings.IsEnabled = true;
            publisher.Advertisement.LegacySettings.Ssid = "DIRECT-FD-FastDropPC";
            
            var cred = new PasswordCredential();
            cred.Password = "fastdrop123";
            publisher.Advertisement.LegacySettings.Passphrase = cred;

            publisher.StatusChanged += (s, e) =>
            {
                Console.WriteLine($"Publisher status: {e.Status}");
            };

            publisher.Start();
            Console.WriteLine($"Advertising as GO.");
            Console.WriteLine($"SSID: {publisher.Advertisement.LegacySettings.Ssid}");
            Console.WriteLine($"Passphrase: fastdrop123");
            Console.WriteLine("Wi-Fi Direct link is up. FastDrop JVM should be running to handle TCP connections.");
            Console.WriteLine("Press ENTER to stop.");
            
            Console.ReadLine();
            publisher.Stop();
        }
    }
}
