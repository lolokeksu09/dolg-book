package dev.ventry.raschet.license

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.ventry.raschet.BuildConfig
import dev.ventry.raschet.LedgerMaterialTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class LicenseActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        if(!BuildConfig.LICENSE_REQUIRED){finish();return}
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE,WindowManager.LayoutParams.FLAG_SECURE)
        val view=ComposeView(this)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        view.setContent{
            LedgerMaterialTheme{
                val context=applicationContext
                var code by remember{mutableStateOf("")}
                var busy by remember{mutableStateOf(false)}
                var confirm by remember{mutableStateOf(false)}
                var status by remember{mutableStateOf(LicenseManager.Overview("Проверка…",""))}
                LaunchedEffect(Unit){status=withContext(Dispatchers.IO){LicenseManager.overview(context)}}
                var message by remember{mutableStateOf("")}
                var pending by remember{mutableStateOf(LicenseManager.hasPending(context))}
                val scope=rememberCoroutineScope()
                fun run(activate:Boolean,retry:Boolean=false){
                    if(busy)return
                    busy=true;message=""
                    val entered=if(retry)null else code.trim()
                    if(activate)code=""
                    scope.launch{
                        try{
                            if(activate)LicenseManager.activate(context,entered) else LicenseManager.refresh(context)
                            message="Ответ сервера проверен."
                        }catch(e:CancellationException){throw e}
                        catch(e:LicenseManager.PendingExists){message="Сначала повторите незавершённую активацию."}
                        catch(e:LicenseManager.Denied){message="Код или привязка недоступны. Обратитесь в Ventry."}
                        catch(e:Exception){message="Не удалось подтвердить доступ. Проверьте соединение и часы. Существующая книга не изменена."}
                        finally{status=withContext(Dispatchers.IO){LicenseManager.overview(context)};pending=LicenseManager.hasPending(context);busy=false}
                    }
                }
                Surface(Modifier.fillMaxSize()){
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
                        Text("Лицензия «Расчёта»",style=MaterialTheme.typography.headlineSmall)
                        Card(Modifier.fillMaxWidth().testTag("license_status")){
                            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                                Text(status.title,style=MaterialTheme.typography.titleLarge,color=if(status.allowed)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                Text(status.detail)
                            }
                        }
                        Text("Что входит в доступ",style=MaterialTheme.typography.titleMedium)
                        Text("Долговая книга, сроки возврата, статистика магазина и клиентов, отчёты, обновления, поддержка и перенос на другое устройство.")
                        Text("После окончания срока просмотр, погашение, исправления и резервные копии остаются доступны. Для новых долгов необходимо продлить доступ. После перезагрузки нужна онлайн-проверка.")
                        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                        OutlinedTextField(code,{code=it.take(64)},Modifier.fillMaxWidth().testTag("license_code"),label={Text("Код активации")},enabled=!busy && !pending,singleLine=true,visualTransformation=PasswordVisualTransformation())
                        Button(onClick={confirm=true},enabled=!busy && !pending && code.trim().token(),modifier=Modifier.fillMaxWidth().testTag("license_activate")){Text("Активировать")}
                        if(pending)OutlinedButton(onClick={run(true,true)},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Повторить активацию")}
                        OutlinedButton(onClick={run(false)},enabled=!busy && !pending,modifier=Modifier.fillMaxWidth().testTag("license_refresh")){Text("Обновить подписку")}
                        if(message.isNotEmpty())Text(message)
                        HorizontalDivider()
                        Text("Продление и помощь",style=MaterialTheme.typography.titleMedium)
                        Text("Оплату и перенос подтверждает Ventry. Автоматических списаний нет.")
                        status.subscription?.let{id->
                            OutlinedButton(onClick={
                                val share=Intent(Intent.ACTION_SEND).apply{
                                    type="text/plain"
                                    putExtra(Intent.EXTRA_TEXT,"Расчёт — номер подписки: "+id)
                                }
                                startActivity(Intent.createChooser(share,"Передать номер подписки"))
                            },enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("share_subscription")){Text("Поделиться номером подписки")}
                        }
                        OutlinedButton(onClick={
                            runCatching{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://ventrylab.top/support/")))}
                                .onFailure{message="Откройте ventrylab.top/support/ в браузере."}
                        },enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Поддержка Ventry")}
                        Text("Тестовая сборка: закрытый сервер, не для коммерческой выдачи.",style=MaterialTheme.typography.bodySmall)
                        TextButton(onClick={finish()},enabled=!busy){Text("Вернуться к книге")}
                    }
                }
                if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("Активировать новый код?")},
                    text={Text("Действующая лицензия заменится только после проверки подписанного ответа. Данные долговой книги останутся без изменений.")},
                    confirmButton={TextButton(onClick={confirm=false;run(true)},modifier=Modifier.testTag("license_confirm")){Text("Активировать")}},
                    dismissButton={TextButton(onClick={confirm=false}){Text("Отмена")}})
            }
        }
        setContentView(view)
    }
}
