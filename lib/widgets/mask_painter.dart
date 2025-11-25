import 'dart:async';
import 'dart:typed_data';
import 'dart:ui' as ui;
import 'package:flutter/material.dart';

class MaskPainter extends CustomPainter {
  final Uint8List? maskData;
  final int maskWidth;
  final int maskHeight;
  final Color overlayColor;
  final double opacity;

  MaskPainter({
    this.maskData,
    required this.maskWidth,
    required this.maskHeight,
    required this.overlayColor,
    this.opacity = 0.5,
  });

  @override
  void paint(Canvas canvas, Size size) {
    if (maskData == null || maskData!.isEmpty) return;

    final paint = Paint()
      ..color = overlayColor.withOpacity(opacity)
      ..blendMode = BlendMode.srcOver;

    final scaleX = size.width / maskWidth;
    final scaleY = size.height / maskHeight;

    for (int y = 0; y < maskHeight; y++) {
      for (int x = 0; x < maskWidth; x++) {
        final index = y * maskWidth + x;
        if (index < maskData!.length && maskData![index] > 127) {
          canvas.drawRect(
            Rect.fromLTWH(x * scaleX, y * scaleY, scaleX + 1, scaleY + 1),
            paint,
          );
        }
      }
    }
  }

  @override
  bool shouldRepaint(covariant MaskPainter oldDelegate) {
    return oldDelegate.maskData != maskData ||
        oldDelegate.overlayColor != overlayColor ||
        oldDelegate.opacity != opacity;
  }
}

class OptimizedMaskPainter extends CustomPainter {
  final ui.Image? maskImage;
  final Color overlayColor;
  final double opacity;

  OptimizedMaskPainter({
    this.maskImage,
    required this.overlayColor,
    this.opacity = 0.5,
  });

  @override
  void paint(Canvas canvas, Size size) {
    if (maskImage == null) return;

    final paint = Paint()
      ..colorFilter = ColorFilter.mode(
        overlayColor.withOpacity(opacity),
        BlendMode.srcIn,
      );

    canvas.drawImageRect(
      maskImage!,
      Rect.fromLTWH(0, 0, maskImage!.width.toDouble(), maskImage!.height.toDouble()),
      Rect.fromLTWH(0, 0, size.width, size.height),
      paint,
    );
  }

  @override
  bool shouldRepaint(covariant OptimizedMaskPainter oldDelegate) {
    return oldDelegate.maskImage != maskImage ||
        oldDelegate.overlayColor != overlayColor ||
        oldDelegate.opacity != opacity;
  }
}

Future<ui.Image?> createMaskImage(Uint8List maskData, int width, int height, Color color) async {
  final pixels = Uint8List(width * height * 4);

  for (int i = 0; i < width * height; i++) {
    final maskValue = i < maskData.length ? maskData[i] : 0;
    if (maskValue > 127) {
      pixels[i * 4] = color.red;
      pixels[i * 4 + 1] = color.green;
      pixels[i * 4 + 2] = color.blue;
      pixels[i * 4 + 3] = (color.alpha * 0.6).round();
    } else {
      pixels[i * 4] = 0;
      pixels[i * 4 + 1] = 0;
      pixels[i * 4 + 2] = 0;
      pixels[i * 4 + 3] = 0;
    }
  }

  final completer = Completer<ui.Image>();
  ui.decodeImageFromPixels(
    pixels,
    width,
    height,
    ui.PixelFormat.rgba8888,
    completer.complete,
  );

  return completer.future;
}

