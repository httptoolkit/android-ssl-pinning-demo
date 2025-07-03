import 'dart:developer' as developer;
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:http/http.dart' as http;

void main() {
  const MethodChannel channel = MethodChannel('tech.httptoolkit.pinning_demo.flutter_channel');
  WidgetsFlutterBinding.ensureInitialized();

  channel.setMethodCallHandler((call) async {
    if (call.method == 'sendRequest') {
      final urlString = call.arguments as String?;
      if (urlString == null || urlString.isEmpty) {
        throw PlatformException(
          code: 'INVALID_ARGUMENT',
          message: 'URL argument is null or empty.',
        );
      }

      developer.log('Dart attempting to send request to: $urlString', name: 'Flutter SendRequest');

      try {
        final url = Uri.parse(urlString);
        final response = await http.get(url);
        developer.log('Got 200 response: ${response.statusCode}', name: 'Flutter SendRequest');
        return 'success';
      } on http.ClientException catch (e) {
        developer.log('Request completed with a non-2xx status code.', name: 'Flutter SendRequest', error: e);
        return 'success'; // We don't care about status codes
      } catch (e, stackTrace) {
        // Any other error:
        developer.log(
          'A network or parsing error occurred.',
          name: 'Flutter SendRequest',
          error: e,
          stackTrace: stackTrace,
        );
        throw PlatformException(
          code: 'NETWORK_ERROR',
          message: 'Failed to send request: ${e.toString()}',
        );
      }
    }
    developer.log('Unknown method call ${call.method}', name: 'Flutter');
  });
}