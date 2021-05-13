cd ./results
for x in "avrora" "luindex" "lusearch" "sunflow" "xalan"
do
	for y in "1" "2" "3" "4" "5" "6"
	do 
		cat results_${x}_FT2_${y} >> ../iresults/results_${x}_FT2_${y}  
	done
done

