using System;
using System.Text.Json;
using System.Threading.Tasks;
using Windows.Devices.WiFiDirect;
using Windows.Security.Credentials;
using Windows.Networking.Sockets;
using System.Linq;

namespace FastDropBridge
{
    class Program
    {
        static WiFiDirectAdvertisementPublisher? publisher;
        static WiFiDirectConnectionListener? listener;

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

            Console.WriteLine("Usage: FastDropBridge.exe spike | spike_autonomous");
            Console.WriteLine("This is a spike to validate Wi-Fi direct interoperability.");
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

                    // Test TCP connection
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
            
            // Legacy Settings
            publisher.Advertisement.LegacySettings.IsEnabled = true;
            publisher.Advertisement.LegacySettings.Ssid = "DIRECT-FD-FastDropPC";
            
            var cred = new PasswordCredential();
            cred.Password = "fastdrop123";
            publisher.Advertisement.LegacySettings.Passphrase = cred;

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

                    // Test TCP connection
                    await TestTcpServer();
                }
                catch (Exception ex)
                {
                    Console.WriteLine("Connection error: " + ex.Message);
                }
            };

            publisher.Start();
            Console.WriteLine($"Advertising as GO.");
            Console.WriteLine($"SSID: {publisher.Advertisement.LegacySettings.Ssid}");
            Console.WriteLine($"Passphrase: fastdrop123");
            Console.WriteLine("Try connecting from Android. Press ENTER to stop.");
            Console.ReadLine();
            publisher.Stop();
        }

        static async Task TestTcpServer()
        {
            try {
                var listener = new StreamSocketListener();
                listener.ConnectionReceived += (s, e) =>
                {
                    Console.WriteLine($"TCP PING received from {e.Socket.Information.RemoteAddress.DisplayName}!");
                    using var writer = new Windows.Storage.Streams.DataWriter(e.Socket.OutputStream);
                    writer.WriteString("PONG\n");
                    writer.StoreAsync().AsTask().Wait();
                    Console.WriteLine("TCP PONG sent!");
                };
                await listener.BindServiceNameAsync("47832");
                Console.WriteLine("TCP Server listening on port 47832");
            } catch (Exception ex) {
                Console.WriteLine("Failed to bind TCP server: " + ex.Message);
            }
        }
    }
}
