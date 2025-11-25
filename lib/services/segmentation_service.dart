import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class SegmentationService {
  static const MethodChannel _channel = MethodChannel('com.example.arcolorchange/segmentation');

  bool _isInitialized = false;
  bool get isInitialized => _isInitialized;

  Future<bool> initialize() async {
    try {
      final result = await _channel.invokeMethod<bool>('initializeModel');
      _isInitialized = result ?? false;
      return _isInitialized;
    } on PlatformException catch (e) {
      debugPrint('Failed to initialize segmentation model: ${e.message}');
      return false;
    }
  }

  Future<Uint8List?> segmentAtPoint({
    required Uint8List imageBytes,
    required int imageWidth,
    required int imageHeight,
    required double tapX,
    required double tapY,
  }) async {
    if (!_isInitialized) {
      throw Exception('Segmentation model not initialized');
    }

    try {
      final result = await _channel.invokeMethod<Uint8List>('segmentAtPoint', {
        'imageBytes': imageBytes,
        'imageWidth': imageWidth,
        'imageHeight': imageHeight,
        'tapX': tapX,
        'tapY': tapY,
      });
      return result;
    } on PlatformException catch (e) {
      debugPrint('Segmentation failed: ${e.message}');
      return null;
    }
  }

  Future<List<Uint8List>?> getAllMasks({
    required Uint8List imageBytes,
    required int imageWidth,
    required int imageHeight,
  }) async {
    if (!_isInitialized) {
      throw Exception('Segmentation model not initialized');
    }

    try {
      final result = await _channel.invokeMethod<List<dynamic>>('getAllMasks', {
        'imageBytes': imageBytes,
        'imageWidth': imageWidth,
        'imageHeight': imageHeight,
      });
      return result?.cast<Uint8List>();
    } on PlatformException catch (e) {
      debugPrint('Get all masks failed: ${e.message}');
      return null;
    }
  }

  Future<void> dispose() async {
    try {
      await _channel.invokeMethod('disposeModel');
      _isInitialized = false;
    } on PlatformException catch (e) {
      debugPrint('Failed to dispose model: ${e.message}');
    }
  }
}
