import 'dart:async';
import 'dart:ui' as ui;
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:camera/camera.dart';
import '../services/segmentation_service.dart';
import '../widgets/color_picker_dialog.dart';

class CameraScreen extends StatefulWidget {
  const CameraScreen({super.key});

  @override
  State<CameraScreen> createState() => _CameraScreenState();
}

class _CameraScreenState extends State<CameraScreen> with WidgetsBindingObserver {
  CameraController? _cameraController;
  List<CameraDescription>? _cameras;
  final SegmentationService _segmentationService = SegmentationService();

  bool _isInitialized = false;
  bool _isProcessing = false;
  bool _modelReady = false;

  Uint8List? _currentMask;
  int _maskWidth = 640;
  int _maskHeight = 640;
  Color _selectedColor = const Color(0xFF87CEEB);
  double _colorOpacity = 0.6;

  ui.Image? _maskImage;
  Offset? _tapPosition;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _initializeCamera();
    _initializeModel();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _cameraController?.dispose();
    _segmentationService.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (_cameraController == null || !_cameraController!.value.isInitialized) {
      return;
    }

    if (state == AppLifecycleState.inactive) {
      _cameraController?.dispose();
    } else if (state == AppLifecycleState.resumed) {
      _initializeCamera();
    }
  }

  Future<void> _initializeCamera() async {
    try {
      _cameras = await availableCameras();
      if (_cameras == null || _cameras!.isEmpty) {
        _showError('No cameras available');
        return;
      }

      final camera = _cameras!.firstWhere(
        (c) => c.lensDirection == CameraLensDirection.back,
        orElse: () => _cameras!.first,
      );

      _cameraController = CameraController(
        camera,
        ResolutionPreset.high,
        enableAudio: false,
        imageFormatGroup: ImageFormatGroup.jpeg,
      );

      await _cameraController!.initialize();

      if (mounted) {
        setState(() {
          _isInitialized = true;
        });
      }
    } catch (e) {
      _showError('Failed to initialize camera: $e');
    }
  }

  Future<void> _initializeModel() async {
    debugPrint('Initializing segmentation model...');
    final success = await _segmentationService.initialize();
    debugPrint('Model initialization result: $success');
    if (mounted) {
      setState(() {
        _modelReady = success;
      });
      if (!success) {
        _showError('Failed to initialize segmentation model');
      }
    }
  }

  void _showError(String message) {
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(message), backgroundColor: Colors.red),
      );
    }
  }

  Future<void> _onTapDown(TapDownDetails details) async {
    if (!_isInitialized || !_modelReady || _isProcessing) {
      debugPrint('Tap ignored: initialized=$_isInitialized, modelReady=$_modelReady, processing=$_isProcessing');
      return;
    }

    setState(() {
      _isProcessing = true;
      _tapPosition = details.localPosition;
    });

    final renderBox = context.findRenderObject() as RenderBox;
    final size = renderBox.size;

    try {
      debugPrint('Taking picture...');
      final image = await _cameraController!.takePicture();
      final imageBytes = await image.readAsBytes();
      debugPrint('Picture taken, size: ${imageBytes.length} bytes');

      final tapX = details.localPosition.dx / size.width;
      final tapY = details.localPosition.dy / size.height;
      debugPrint('Tap position: ($tapX, $tapY)');

      final decodedImage = await decodeImageFromList(imageBytes);
      debugPrint('Decoded image: ${decodedImage.width}x${decodedImage.height}');

      final mask = await _segmentationService.segmentAtPoint(
        imageBytes: imageBytes,
        imageWidth: decodedImage.width,
        imageHeight: decodedImage.height,
        tapX: tapX,
        tapY: tapY,
      );

      debugPrint('Mask received: ${mask?.length ?? 0} bytes');

      if (mask != null && mounted) {
        // Count non-zero pixels in mask
        int nonZeroCount = 0;
        for (int i = 0; i < mask.length; i++) {
          // Use unsigned comparison
          if ((mask[i] & 0xFF) > 127) {
            nonZeroCount++;
          }
        }
        debugPrint('Non-zero pixels in mask: $nonZeroCount');

        _maskWidth = 640;
        _maskHeight = 640;

        final maskImg = await _createMaskImage(mask);
        debugPrint('Mask image created: ${maskImg.width}x${maskImg.height}');

        setState(() {
          _currentMask = mask;
          _maskImage = maskImg;
        });
      }
    } catch (e, stackTrace) {
      debugPrint('Segmentation failed: $e');
      debugPrint('Stack trace: $stackTrace');
      _showError('Segmentation failed: $e');
    } finally {
      if (mounted) {
        setState(() {
          _isProcessing = false;
        });
      }
    }
  }

  Future<ui.Image> _createMaskImage(Uint8List maskData) async {
    final pixels = Uint8List(_maskWidth * _maskHeight * 4);
    int coloredPixels = 0;

    for (int i = 0; i < _maskWidth * _maskHeight; i++) {
      // Use unsigned byte comparison
      final maskValue = i < maskData.length ? (maskData[i] & 0xFF) : 0;
      if (maskValue > 127) {
        pixels[i * 4] = _selectedColor.red;
        pixels[i * 4 + 1] = _selectedColor.green;
        pixels[i * 4 + 2] = _selectedColor.blue;
        pixels[i * 4 + 3] = (_colorOpacity * 255).round();
        coloredPixels++;
      } else {
        pixels[i * 4] = 0;
        pixels[i * 4 + 1] = 0;
        pixels[i * 4 + 2] = 0;
        pixels[i * 4 + 3] = 0;
      }
    }

    debugPrint('Created mask image with $coloredPixels colored pixels');

    final completer = Completer<ui.Image>();
    ui.decodeImageFromPixels(
      pixels,
      _maskWidth,
      _maskHeight,
      ui.PixelFormat.rgba8888,
      completer.complete,
    );

    return completer.future;
  }

  Future<void> _updateMaskColor() async {
    if (_currentMask != null) {
      final maskImg = await _createMaskImage(_currentMask!);
      if (mounted) {
        setState(() {
          _maskImage = maskImg;
        });
      }
    }
  }

  void _openColorPicker() async {
    final color = await showColorPickerDialog(context, _selectedColor);
    if (color != null) {
      setState(() {
        _selectedColor = color;
      });
      _updateMaskColor();
    }
  }

  void _clearMask() {
    setState(() {
      _currentMask = null;
      _maskImage = null;
      _tapPosition = null;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        fit: StackFit.expand,
        children: [
          // Camera preview layer
          if (_isInitialized && _cameraController != null)
            ClipRect(
              child: OverflowBox(
                alignment: Alignment.center,
                child: FittedBox(
                  fit: BoxFit.cover,
                  child: SizedBox(
                    width: _cameraController!.value.previewSize?.height ?? 1,
                    height: _cameraController!.value.previewSize?.width ?? 1,
                    child: CameraPreviewWidget(controller: _cameraController!),
                  ),
                ),
              ),
            )
          else
            const Center(
              child: CircularProgressIndicator(color: Colors.white),
            ),

          // Mask overlay layer
          if (_maskImage != null)
            Positioned.fill(
              child: CustomPaint(
                painter: _MaskOverlayPainter(maskImage: _maskImage!),
              ),
            ),

          // Tap gesture detector (transparent overlay)
          if (_isInitialized && _cameraController != null)
            Positioned.fill(
              child: GestureDetector(
                onTapDown: _onTapDown,
                behavior: HitTestBehavior.translucent,
                child: Container(color: Colors.transparent),
              ),
            ),

          // Processing indicator
          if (_isProcessing)
            Container(
              color: Colors.black26,
              child: const Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    CircularProgressIndicator(color: Colors.white),
                    SizedBox(height: 16),
                    Text(
                      'Segmenting...',
                      style: TextStyle(color: Colors.white, fontSize: 16),
                    ),
                  ],
                ),
              ),
            ),

          // Tap position indicator
          if (_tapPosition != null && !_isProcessing)
            Positioned(
              left: _tapPosition!.dx - 15,
              top: _tapPosition!.dy - 15,
              child: Container(
                width: 30,
                height: 30,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  border: Border.all(color: Colors.white, width: 2),
                  color: Colors.white24,
                ),
              ),
            ),

          // UI Controls
          SafeArea(
            child: Column(
              children: [
                _buildTopBar(),
                const Spacer(),
                _buildBottomControls(),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildTopBar() {
    return Container(
      padding: const EdgeInsets.all(16),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
            decoration: BoxDecoration(
              color: _modelReady ? Colors.green : Colors.orange,
              borderRadius: BorderRadius.circular(20),
            ),
            child: Text(
              _modelReady ? 'Model Ready' : 'Loading Model...',
              style: const TextStyle(color: Colors.white, fontSize: 12),
            ),
          ),
          const Spacer(),
          if (_currentMask != null)
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
              decoration: BoxDecoration(
                color: Colors.blue,
                borderRadius: BorderRadius.circular(12),
              ),
              child: Text(
                'Mask: ${_currentMask!.where((b) => (b & 0xFF) > 127).length} px',
                style: const TextStyle(color: Colors.white, fontSize: 10),
              ),
            ),
          if (_currentMask != null)
            IconButton(
              onPressed: _clearMask,
              icon: const Icon(Icons.clear, color: Colors.white),
              tooltip: 'Clear selection',
            ),
        ],
      ),
    );
  }

  Widget _buildBottomControls() {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.bottomCenter,
          end: Alignment.topCenter,
          colors: [
            Colors.black.withOpacity(0.8),
            Colors.transparent,
          ],
        ),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Text(
            'Tap on a wall to select it',
            style: TextStyle(color: Colors.white70, fontSize: 14),
          ),
          const SizedBox(height: 16),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceEvenly,
            children: [
              _buildControlButton(
                icon: Icons.color_lens,
                label: 'Color',
                color: _selectedColor,
                onTap: _openColorPicker,
              ),
              _buildOpacitySlider(),
              _buildControlButton(
                icon: Icons.refresh,
                label: 'Reset',
                onTap: _clearMask,
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildControlButton({
    required IconData icon,
    required String label,
    Color? color,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 56,
            height: 56,
            decoration: BoxDecoration(
              color: color ?? Colors.white24,
              shape: BoxShape.circle,
              border: Border.all(color: Colors.white, width: 2),
            ),
            child: Icon(icon, color: color != null ? _getContrastColor(color) : Colors.white),
          ),
          const SizedBox(height: 4),
          Text(label, style: const TextStyle(color: Colors.white, fontSize: 12)),
        ],
      ),
    );
  }

  Widget _buildOpacitySlider() {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        const Text('Opacity', style: TextStyle(color: Colors.white, fontSize: 12)),
        SizedBox(
          width: 150,
          child: Slider(
            value: _colorOpacity,
            min: 0.1,
            max: 1.0,
            activeColor: _selectedColor,
            inactiveColor: Colors.white24,
            onChanged: (value) {
              setState(() {
                _colorOpacity = value;
              });
              _updateMaskColor();
            },
          ),
        ),
      ],
    );
  }

  Color _getContrastColor(Color color) {
    final luminance = color.computeLuminance();
    return luminance > 0.5 ? Colors.black : Colors.white;
  }
}

class CameraPreviewWidget extends StatelessWidget {
  final CameraController controller;

  const CameraPreviewWidget({super.key, required this.controller});

  @override
  Widget build(BuildContext context) {
    return controller.buildPreview();
  }
}

class _MaskOverlayPainter extends CustomPainter {
  final ui.Image maskImage;

  _MaskOverlayPainter({required this.maskImage});

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..filterQuality = FilterQuality.medium
      ..isAntiAlias = true;

    final srcRect = Rect.fromLTWH(
      0,
      0,
      maskImage.width.toDouble(),
      maskImage.height.toDouble(),
    );

    final dstRect = Rect.fromLTWH(0, 0, size.width, size.height);

    canvas.drawImageRect(maskImage, srcRect, dstRect, paint);
  }

  @override
  bool shouldRepaint(covariant _MaskOverlayPainter oldDelegate) {
    return oldDelegate.maskImage != maskImage;
  }
}
