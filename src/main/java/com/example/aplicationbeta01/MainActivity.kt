package com.example.aplicationbeta01

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.hardware.display.DisplayManager
import android.view.View
import android.view.Surface
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.graphics.Bitmap
import kotlin.math.max
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                CameraScreen()
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    var hasCameraPermission by remember { mutableStateOf(false) }

    // Launcher para pedir permissão de câmera
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission = granted }
    )

    // Dispara o pedido de permissão assim que a tela carrega
    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (hasCameraPermission) {
        CameraPreviewContainer()
    } else {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "Aguardando permissão da câmera...")
        }
    }
}

@ExperimentalGetImage
@Composable
fun CameraPreviewContainer() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Status para exibição de texto
    var statusText by remember { mutableStateOf("Inicializando FaceLandmarker...") }
    var landmarkerError by remember { mutableStateOf<String?>(null) }

    // Estados da simulação de cabelo e barba
    var currentLandmarksResult by remember { mutableStateOf<FaceLandmarkerResult?>(null) }
    var selectedHairStyle by rememberSaveable { mutableStateOf(HairStyle.NONE) }
    var selectedBeardStyle by rememberSaveable { mutableStateOf(BeardStyle.NONE) }
    
    // Carregar as amostras de imagem como ImageBitmap (com cache em remember para performance)
    val amostra01 = remember(context) {
        BitmapFactory.decodeResource(context.resources, R.drawable.amostra01).asImageBitmap()
    }
    val amostra02 = remember(context) {
        BitmapFactory.decodeResource(context.resources, R.drawable.amostra02).asImageBitmap()
    }
    val corte002 = remember(context) {
        BitmapFactory.decodeResource(context.resources, R.drawable.corte_cabelo_masculino_002).asImageBitmap()
    }
    val corte003 = remember(context) {
        BitmapFactory.decodeResource(context.resources, R.drawable.corte_cabelo_masculino_003).asImageBitmap()
    }
    
    // Dimensões do frame para mapeamento de coordenadas
    var frameWidth by remember { mutableStateOf(1) }
    var frameHeight by remember { mutableStateOf(1) }
    var frameRotation by remember { mutableStateOf(0) }

    // Criamos o executor de segundo plano para processar as imagens
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    // Criamos o FaceLandmarker
    val faceLandmarker = remember(context) {
        try {
            setupFaceLandmarker(
                context = context,
                onResults = { result, _ ->
                    val faceCount = result.faceLandmarks().size
                    statusText = if (faceCount > 0) {
                        "Status: Rosto detectado!\nPontos faciais: ${result.faceLandmarks()[0].size}"
                    } else {
                        "Status: Nenhum rosto detectado"
                    }
                    currentLandmarksResult = result
                },
                onError = { error ->
                    Log.e("FaceLandmarker", "Erro no listener do FaceLandmarker: ${error.message}")
                    statusText = "Erro: ${error.localizedMessage}"
                }
            )
        } catch (e: Exception) {
            Log.e("FaceLandmarker", "Erro ao inicializar FaceLandmarker: ${e.message}", e)
            landmarkerError = e.message ?: e.toString()
            null
        }
    }

    // Libera os recursos quando o Composable for descartado
    DisposableEffect(faceLandmarker) {
        onDispose {
            faceLandmarker?.close()
            analysisExecutor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (landmarkerError != null) {
            // Se houver erro de inicialização (ex: falta o arquivo .task), avisa na tela de forma amigável
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF2C1B1B))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Erro ao inicializar FaceLandmarker:\n$landmarkerError\n\nCertifique-se de que o arquivo 'face_landmarker.task' está na pasta 'assets' do seu projeto.",
                    color = Color.Red,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            // Exibe a visualização da câmera
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val executor = ContextCompat.getMainExecutor(ctx)

                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()

                        // Rotação inicial da tela
                        val initialRotation = previewView.display?.rotation ?: Surface.ROTATION_0

                        val preview = Preview.Builder()
                            .setTargetRotation(initialRotation)
                            .build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }

                        // Configura o analisador de imagem do CameraX
                        val imageAnalyzer = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .setTargetRotation(initialRotation)
                            .build()
                            .also { analyzer ->
                                analyzer.setAnalyzer(analysisExecutor) { imageProxy ->
                                    if (faceLandmarker != null) {
                                        try {
                                            // 1. Criamos um bitmap buffer com as dimensões do frame
                                            val bitmapBuffer = Bitmap.createBitmap(
                                                imageProxy.width,
                                                imageProxy.height,
                                                Bitmap.Config.ARGB_8888
                                            )
                                            // 2. Copiamos os pixels do buffer do ImageProxy para o bitmap
                                            bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer)

                                            // 3. Converte o frame em MPImage
                                            val mpImage = BitmapImageBuilder(bitmapBuffer).build()

                                            // 4. Define a rotação correta baseada nas informações do frame do CameraX
                                            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                                            
                                            // Atualiza as dimensões e rotação do frame para o Canvas
                                            frameWidth = imageProxy.width
                                            frameHeight = imageProxy.height
                                            frameRotation = rotationDegrees
                                            
                                            val imageProcessingOptions = ImageProcessingOptions.builder()
                                                .setRotationDegrees(rotationDegrees)
                                                .build()

                                            // 5. Obtém o timestamp atual (monotonicamente crescente em milissegundos)
                                            val timestampMs = SystemClock.uptimeMillis()

                                            // 6. Envia o frame assincronamente para detecção de landmarks faciais
                                            faceLandmarker.detectAsync(mpImage, imageProcessingOptions, timestampMs)
                                        } catch (e: Exception) {
                                            Log.e("FaceLandmarker", "Erro ao processar frame: ${e.message}", e)
                                        }
                                    }
                                    // 7. IMPORTANTE: Fecha o ImageProxy para liberar o buffer e continuar recebendo frames
                                    imageProxy.close()
                                }
                            }

                        // Configura o DisplayListener para atualizar a rotação das use cases quando o smartphone for rotacionado
                        val displayManager = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                        val displayListener = object : DisplayManager.DisplayListener {
                            override fun onDisplayAdded(displayId: Int) {}
                            override fun onDisplayRemoved(displayId: Int) {}
                            override fun onDisplayChanged(displayId: Int) {
                                val viewDisplay = previewView.display
                                if (viewDisplay != null && displayId == viewDisplay.displayId) {
                                    val rotation = viewDisplay.rotation
                                    try {
                                        preview.targetRotation = rotation
                                        imageAnalyzer.targetRotation = rotation
                                    } catch (e: Exception) {
                                        Log.e("CameraPreview", "Erro ao atualizar rotação: ${e.message}")
                                    }
                                }
                            }
                        }

                        val attachListener = object : View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(v: View) {
                                displayManager.registerDisplayListener(displayListener, null)
                            }

                            override fun onViewDetachedFromWindow(v: View) {
                                displayManager.unregisterDisplayListener(displayListener)
                            }
                        }

                        previewView.addOnAttachStateChangeListener(attachListener)
                        if (previewView.isAttachedToWindow) {
                            displayManager.registerDisplayListener(displayListener, null)
                        }

                        // Seleciona a câmera frontal por padrão (melhor para o simulador)
                        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageAnalyzer
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }, executor)

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            // Canvas de desenho por cima da visualização da câmera
            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasWidth = size.width
                val canvasHeight = size.height

                val currentResult = currentLandmarksResult
                if (currentResult != null && currentResult.faceLandmarks().isNotEmpty() && frameWidth > 1 && frameHeight > 1) {
                    val landmarks = currentResult.faceLandmarks()[0]

                    // 1. Calcula as dimensões rotacionadas baseadas na rotação do frame
                    val rotatedWidth = if (frameRotation == 90 || frameRotation == 270) frameHeight else frameWidth
                    val rotatedHeight = if (frameRotation == 90 || frameRotation == 270) frameWidth else frameHeight

                    val scaleX = canvasWidth / rotatedWidth.toFloat()
                    val scaleY = canvasHeight / rotatedHeight.toFloat()
                    val scale = max(scaleX, scaleY)

                    val offsetX = (canvasWidth - rotatedWidth * scale) / 2f
                    val offsetY = (canvasHeight - rotatedHeight * scale) / 2f

                    // 2. Mapeia todos os pontos normalizados para pixels da tela (com espelhamento da câmera frontal baseado na rotação do frame)
                    val points = landmarks.map { landmark ->
                        // Em modo retrato (frameRotation = 90 ou 270), o eixo horizontal do display é mapeado para o eixo vertical do sensor (não espelhado),
                        // então precisamos espelhá-lo manualmente para corresponder ao preview da câmera frontal.
                        // Em modo paisagem (frameRotation = 0 ou 180), o eixo horizontal do display é o próprio eixo horizontal do sensor (já espelhado).
                        val correctedX = if (frameRotation == 90 || frameRotation == 270) {
                            1f - landmark.x()
                        } else {
                            landmark.x()
                        }
                        val screenX = correctedX * rotatedWidth * scale + offsetX
                        val screenY = landmark.y() * rotatedHeight * scale + offsetY
                        android.graphics.PointF(screenX, screenY)
                    }

                    val hairColor = Color(0xFF1E1E1E) // Cor do cabelo (preto)
                    val beardColor = Color(0xFF2E241E) // Cor da barba (castanho escuro)

                    // Direção vertical do rosto (vetor do queixo à testa)
                    val vX = points[10].x - points[152].x
                    val vY = points[10].y - points[152].y

                    // --- DESENHO DE BARBA E CAVANHAQUE ---

                    // Bigode (Mustache)
                    if (selectedBeardStyle == BeardStyle.MUSTACHE || selectedBeardStyle == BeardStyle.FULL) {
                        val path = Path().apply {
                            val noseBase = points[164]
                            val leftCorner = points[61]
                            val rightCorner = points[291]
                            val lipCenter = points[0]
                            moveTo(leftCorner.x, leftCorner.y)
                            quadraticTo(points[37].x, points[37].y - (points[164].y - points[0].y) * 0.4f, noseBase.x, noseBase.y)
                            quadraticTo(points[267].x, points[267].y - (points[164].y - points[0].y) * 0.4f, rightCorner.x, rightCorner.y)
                            quadraticTo(points[308].x, points[308].y + (points[17].y - points[0].y) * 0.1f, lipCenter.x, lipCenter.y)
                            quadraticTo(points[78].x, points[78].y + (points[17].y - points[0].y) * 0.1f, leftCorner.x, leftCorner.y)
                            close()
                        }
                        drawPath(path = path, color = beardColor, style = Fill)
                    }

                    // Cavanhaque (Goatee)
                    if (selectedBeardStyle == BeardStyle.GOATEE) {
                        val path = Path().apply {
                            moveTo(points[61].x, points[61].y)
                            lineTo(points[148].x, points[148].y)
                            lineTo(points[152].x, points[152].y)
                            lineTo(points[377].x, points[377].y)
                            lineTo(points[291].x, points[291].y)
                            quadraticTo(points[17].x, points[17].y + (points[152].y - points[17].y) * 0.2f, points[61].x, points[61].y)
                            close()
                        }
                        drawPath(path = path, color = beardColor, style = Fill)
                    }

                    // Barba Cheia (Full Beard)
                    if (selectedBeardStyle == BeardStyle.FULL) {
                        val path = Path().apply {
                            moveTo(points[127].x, points[127].y)
                            lineTo(points[234].x, points[234].y)
                            lineTo(points[172].x, points[172].y)
                            lineTo(points[150].x, points[150].y)
                            lineTo(points[152].x, points[152].y)
                            lineTo(points[379].x, points[379].y)
                            lineTo(points[400].x, points[400].y)
                            lineTo(points[454].x, points[454].y)
                            lineTo(points[356].x, points[356].y)
                            lineTo(points[411].x, points[411].y)
                            lineTo(points[291].x, points[291].y)
                            lineTo(points[17].x, points[17].y)
                            lineTo(points[61].x, points[61].y)
                            lineTo(points[187].x, points[187].y)
                            lineTo(points[127].x, points[127].y)
                            close()
                        }
                        drawPath(path = path, color = beardColor, style = Fill)
                    }

                    // --- DESENHO DE CABELO ---

                    // Buzzcut (Militar)
                    if (selectedHairStyle == HairStyle.BUZZCUT) {
                        val path = Path().apply {
                            val pTopX = points[10].x + vX * 0.22f
                            val pTopY = points[10].y + vY * 0.22f
                            val pLeftX = points[109].x + vX * 0.12f
                            val pLeftY = points[109].y + vY * 0.12f
                            val pRightX = points[338].x + vX * 0.12f
                            val pRightY = points[338].y + vY * 0.12f

                            moveTo(points[109].x, points[109].y)
                            lineTo(pLeftX, pLeftY)
                            quadraticTo(pTopX, pTopY, pRightX, pRightY)
                            lineTo(points[338].x, points[338].y)
                            lineTo(points[10].x, points[10].y)
                            close()
                        }
                        drawPath(path = path, color = hairColor, style = Fill)
                    }

                    // Mohawk (Moicano)
                    if (selectedHairStyle == HairStyle.MOHAWK) {
                        val path = Path().apply {
                            val pTopX = points[10].x + vX * 0.45f
                            val pTopY = points[10].y + vY * 0.45f

                            val orthoX = -vY * 0.08f
                            val orthoY = vX * 0.08f

                            val pLeftBaseX = points[10].x + orthoX
                            val pLeftBaseY = points[10].y + orthoY
                            val pRightBaseX = points[10].x - orthoX
                            val pRightBaseY = points[10].y - orthoY

                            moveTo(pLeftBaseX, pLeftBaseY)
                            lineTo(points[10].x + vX * 0.15f + orthoX * 0.8f, points[10].y + vY * 0.15f + orthoY * 0.8f)
                            lineTo(pTopX, pTopY)
                            lineTo(points[10].x + vX * 0.15f - orthoX * 0.8f, points[10].y + vY * 0.15f - orthoY * 0.8f)
                            lineTo(pRightBaseX, pRightBaseY)
                            close()
                        }
                        drawPath(path = path, color = hairColor, style = Fill)
                    }

                    // Fringe (Franja)
                    if (selectedHairStyle == HairStyle.FRINGE) {
                        val path = Path().apply {
                            val pTopX = points[10].x + vX * 0.25f
                            val pTopY = points[10].y + vY * 0.25f
                            val pLeftX = points[109].x + vX * 0.15f
                            val pLeftY = points[109].y + vY * 0.15f
                            val pRightX = points[338].x + vX * 0.15f
                            val pRightY = points[338].y + vY * 0.15f

                            val pFringeLeftX = points[70].x
                            val pFringeLeftY = points[70].y
                            val pFringeRightX = points[300].x
                            val pFringeRightY = points[300].y

                            moveTo(points[109].x, points[109].y)
                            lineTo(pLeftX, pLeftY)
                            quadraticTo(pTopX, pTopY, pRightX, pRightY)
                            lineTo(points[338].x, points[338].y)
                            lineTo(pFringeRightX, pFringeRightY)
                            lineTo(points[10].x - vX * 0.05f, points[10].y - vY * 0.05f)
                            lineTo(pFringeLeftX, pFringeLeftY)
                            close()
                        }
                        drawPath(path = path, color = hairColor, style = Fill)
                    }

                    // Desenho de Imagens de Amostra (Amostras de cabelo)
                    val hairBitmap = when (selectedHairStyle) {
                        HairStyle.AM_01 -> amostra01
                        HairStyle.AM_02 -> amostra02
                        HairStyle.CORTE_002 -> corte002
                        HairStyle.CORTE_003 -> corte003
                        else -> null
                    }

                    if (hairBitmap != null) {
                        // Em retrato (90/270), points[454] é a bochecha esquerda no lado esquerdo da tela (menor X) e points[234] é a bochecha direita no lado direito da tela (maior X).
                        // Em paisagem (0/180), como não espelhamos, points[454] está no lado direito da tela (maior X) e points[234] está no lado esquerdo da tela (menor X).
                        // Definimos pLeft como a bochecha esquerda da tela (menor X) e pRight como a bochecha direita da tela (maior X) para manter a rotação consistente (dx positivo).
                        val pLeft = if (frameRotation == 90 || frameRotation == 270) points[454] else points[234]
                        val pRight = if (frameRotation == 90 || frameRotation == 270) points[234] else points[454]
                        val dx = pRight.x - pLeft.x
                        val dy = pRight.y - pLeft.y

                        // Ângulo de inclinação lateral da cabeça
                        val angle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()

                        // Distância horizontal entre as bochechas
                        val faceWidth = kotlin.math.sqrt(dx * dx + dy * dy)

                        // Fator de escala do cabelo (customizável por estilo)
                        val hairScaleMultiplier = selectedHairStyle.hairScaleMultiplier
                        val hairWidth = faceWidth * hairScaleMultiplier
                        val hairHeight = hairWidth * (hairBitmap.height.toFloat() / hairBitmap.width.toFloat())

                        // Ponto de pivot no topo da testa (landmark 10)
                        val pivotPoint = points[10]

                        // Aplicamos a rotação na DrawScope
                        rotate(degrees = angle, pivot = Offset(pivotPoint.x, pivotPoint.y)) {
                            // Ajuste vertical (customizável por estilo) para que a linha do cabelo
                            // das amostras se sobreponha de forma suave e natural sobre a testa.
                            val verticalAdjustment = hairHeight * selectedHairStyle.verticalAdjustmentFactor
                            val left = pivotPoint.x - (hairWidth / 2f)
                            val top = pivotPoint.y - hairHeight + verticalAdjustment

                            drawImage(
                                image = hairBitmap,
                                dstOffset = IntOffset(left.toInt(), top.toInt()),
                                dstSize = IntSize(hairWidth.toInt(), hairHeight.toInt())
                            )
                        }
                    }
                }
            }

            // Painel moderno sobreposto (overlay) com seletores de estilo
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = if (isLandscape) Alignment.CenterEnd else Alignment.BottomCenter
            ) {
                Column(
                    modifier = Modifier
                        .then(
                            if (isLandscape) {
                                Modifier.fillMaxHeight().width(320.dp)
                            } else {
                                Modifier.fillMaxWidth()
                            }
                        )
                        .background(
                            color = Color(0xD90F172A), // Dark slate semi-transparente
                            shape = RoundedCornerShape(20.dp)
                        )
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = if (isLandscape) Arrangement.Center else Arrangement.Top
                ) {
                    // Status da Detecção
                    Text(
                        text = statusText,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    // Seletor de Cabelo
                    Text(
                        text = "Estilo de Cabelo:",
                        color = Color(0xFF94A3B8), // Slate 400
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .align(Alignment.Start)
                            .padding(bottom = 6.dp)
                    )

                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(HairStyle.values()) { style ->
                            val isSelected = selectedHairStyle == style
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = if (isSelected) Color(0xFF3B82F6) else Color(0xFF1E293B), // Blue 500 ou Slate 800
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable { selectedHairStyle = style }
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = style.label,
                                    color = if (isSelected) Color.White else Color(0xFFCBD5E1), // White ou Slate 300
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }

                    // Seletor de Barba
                    Text(
                        text = "Estilo de Barba:",
                        color = Color(0xFF94A3B8),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .align(Alignment.Start)
                            .padding(bottom = 6.dp)
                    )

                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(BeardStyle.values()) { style ->
                            val isSelected = selectedBeardStyle == style
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = if (isSelected) Color(0xFF10B981) else Color(0xFF1E293B), // Emerald 500 ou Slate 800
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable { selectedBeardStyle = style }
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = style.label,
                                    color = if (isSelected) Color.White else Color(0xFFCBD5E1),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

fun setupFaceLandmarker(
        context: android.content.Context,
        onResults: (FaceLandmarkerResult, MPImage) -> Unit,
        onError: (Throwable) -> Unit
    ): FaceLandmarker {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task") // Nome exato do arquivo no assets
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinFaceDetectionConfidence(0.5f)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result, mpImage ->
                    onResults(result, mpImage)
                }
                .setErrorListener { error ->
                    onError(error)
                }
                .build()

            return FaceLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            Log.e("MediaPipeError", "Falha ao inicializar o FaceLandmarker", e)
            throw e // Propaga o erro para o tratamento na UI
        }
}

enum class HairStyle(
    val label: String,
    val drawableRes: Int? = null,
    val hairScaleMultiplier: Float = 1.4f,
    val verticalAdjustmentFactor: Float = 0.18f
) {
    NONE("Nenhum"),
    MOHAWK("Moicano"),
    FRINGE("Franja"),
    BUZZCUT("Militar"),
    AM_01("Social (Amostra 1)", R.drawable.amostra01),
    AM_02("Moderno (Amostra 2)", R.drawable.amostra02),
    CORTE_002("Cabelo Masculino 2", R.drawable.corte_cabelo_masculino_002, 1.4f, 0.18f),
    CORTE_003("Cabelo Masculino 3", R.drawable.corte_cabelo_masculino_003, 1.4f, 0.18f)
}

enum class BeardStyle(val label: String) {
    NONE("Nenhuma"),
    MUSTACHE("Bigode"),
    GOATEE("Cavanhaque"),
    FULL("Barba Cheia")
}
