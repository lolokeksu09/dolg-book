package dev.ventry.raschet.license

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
        setContent{
            LedgerMaterialTheme{
                val context=applicationContext
                var code by remember{mutableStateOf("")}
                var busy by remember{mutableStateOf(false)}
                var confirm by remember{mutableStateOf(false)}
                var status by remember{mutableStateOf(LicenseManager.description(context))}
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
                        catch(e:LicenseManager.Denied){message="Код или привязка недоступны. Обратитесь в Ventry."}
                        catch(e:Exception){message="Не удалось подтвердить доступ. Проверьте соединение и часы. Существующая книга не изменена."}
                        finally{status=LicenseManager.description(context);pending=LicenseManager.hasPending(context);busy=false}
                    }
                }
                Surface(Modifier.fillMaxSize()){
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
                        Text("Лицензия «Расчёта»",style=MaterialTheme.typography.headlineSmall)
                        Text(status)
                        Text("Просмотр, погашение долгов, исправления и резервные копии остаются доступны. Новые долги требуют активной лицензии. После перезагрузки нужна онлайн-проверка.")
                        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                        OutlinedTextField(code,{code=it.take(64)},Modifier.fillMaxWidth(),label={Text("Код активации")},enabled=!busy,singleLine=true,visualTransformation=PasswordVisualTransformation())
                        Button(onClick={confirm=true},enabled=!busy && code.trim().token(),modifier=Modifier.fillMaxWidth()){Text("Активировать")}
                        if(pending)OutlinedButton(onClick={run(true,true)},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Повторить активацию")}
                        OutlinedButton(onClick={run(false)},enabled=!busy && !pending,modifier=Modifier.fillMaxWidth()){Text("Обновить подписку")}
                        if(message.isNotEmpty())Text(message)
                        Text("Тестовая сборка: закрытый сервер, не для коммерческой выдачи.")
                        TextButton(onClick={finish()},enabled=!busy){Text("Вернуться к книге")}
                    }
                }
                if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("Активировать новый код?")},
                    text={Text("Предыдущее локальное разрешение будет заменено. Данные долговой книги останутся без изменений.")},
                    confirmButton={TextButton(onClick={confirm=false;run(true)}){Text("Активировать")}},
                    dismissButton={TextButton(onClick={confirm=false}){Text("Отмена")}})
            }
        }
    }
}
