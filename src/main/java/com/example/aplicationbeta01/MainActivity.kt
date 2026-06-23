package com.example.aplicationbeta01

import android.Manifest
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.framework.image.MediaImageBuilder
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

    // Status para exibição de texto
    var statusText by remember { mutableStateOf("Inicializando FaceLandmarker...") }
    var landmarkerError by remember { mutableStateOf<String?>(null) }

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
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        // Configura o analisador de imagem do CameraX
                        val imageAnalyzer = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { analyzer ->
                                analyzer.setAnalyzer(analysisExecutor) { imageProxy ->
                                    val mediaImage = imageProxy.image
                                    if (mediaImage != null && faceLandmarker != null) {
                                        try {
                                            // 1. Converte o frame da câmera (ImageProxy/Image) em MPImage
                                            val mpImage = MediaImageBuilder(mediaImage).build()

                                            // 2. Define a rotação correta baseada nas informações do frame do CameraX
                                            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                                            val imageProcessingOptions = ImageProcessingOptions.builder()
                                                .setRotationDegrees(rotationDegrees)
                                                .build()

                                            // 3. Obtém o timestamp atual (monotonicamente crescente em milissegundos)
                                            val timestampMs = SystemClock.uptimeMillis()

                                            // 4. Envia o frame assincronamente para detecção de landmarks faciais
                                            faceLandmarker.detectAsync(mpImage, imageProcessingOptions, timestampMs)
                                        } catch (e: Exception) {
                                            Log.e("FaceLandmarker", "Erro ao processar frame: ${e.message}", e)
                                        }
                                    }
                                    // 5. IMPORTANTE: Fecha o ImageProxy para liberar o buffer e continuar recebendo frames
                                    imageProxy.close()
                                }
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

            // Painel moderno sobreposto (overlay) com status da detecção e visual limpo (Glassmorphism/Sleek design)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .background(
                            color = Color(0x990F172A), // Dark slate semi-transparente
                            shape = RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .wrapContentSize()
                ) {
                    Text(
                        text = statusText,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
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
    val baseOptions = BaseOptions.builder()
        .setModelAssetPath("face_landmarker.task") // Nome exato do arquivo no assets
        .build()

    val options = FaceLandmarker.FaceLandmarkerOptions.builder()
        .setBaseOptions(baseOptions)
        .setMinFaceDetectionConfidence(0.5f) // Confiança mínima para detectar um rosto
        .setRunningMode(RunningMode.LIVE_STREAM) // Configura para ler o feed da câmera em tempo real
        .setResultListener { result, mpImage ->
            onResults(result, mpImage)
        }
        .setErrorListener { error ->
            onError(error)
        }
        .build()

    return FaceLandmarker.createFromOptions(context, options)
}
